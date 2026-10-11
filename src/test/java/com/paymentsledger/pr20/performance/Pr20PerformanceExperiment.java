package com.paymentsledger.pr20.performance;

import com.fasterxml.jackson.databind.*;
import com.paymentsledger.notification.ConsumerInboxService;
import com.paymentsledger.payment.PaymentIds;
import com.paymentsledger.notification.fixture.SimulatedMerchantServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.TopicPartition;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Run with the compiled test classpath. Every invocation owns fresh containers and a new immutable evidence directory. */
public final class Pr20PerformanceExperiment {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    final Options options;
    final Path output;
    final List<Actor> actors=new ArrayList<>();
    final List<String> queryIds=Collections.synchronizedList(new ArrayList<>());
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final AtomicLong ordinal=new AtomicLong();
    final AtomicInteger outstanding=new AtomicInteger();
    final AtomicBoolean samplerStop=new AtomicBoolean();
    final AtomicReference<String> abortReason=new AtomicReference<>();
    PostgreSQLContainer<?> ownedDatabase;
    volatile Phase activePhase;
    final List<Map<String,Object>> samples=Collections.synchronizedList(new ArrayList<>());
    final List<String> instrumentationErrors=Collections.synchronizedList(new ArrayList<>());
    JdbcTemplate admin,external;
    Instant loadStart,historicalCutoff;
    String topic,bootstrap;
    final Map<String,Object> summary=new LinkedHashMap<>();
    Pr20PerformanceExperiment(Options options)throws IOException{
        this.options=options;this.output=options.output.toAbsolutePath();Files.createDirectories(output.getParent());Files.createDirectory(output);
    }
    public static void main(String[] args)throws Exception{
        Options options=Options.parse(args);
        var experiment=new Pr20PerformanceExperiment(options);
        try{experiment.run();}catch(Throwable failure){
            experiment.summary.put("status",experiment.loadStart==null?"BLOCKED":"FAIL");
            experiment.summary.put("failureType",failure.getClass().getName());if(experiment.activePhase!=null)experiment.summary.put("partialPhase",experiment.activePhase.map());experiment.summary.put("failureMessage",safeMessage(failure));
            try(var log=Files.newBufferedWriter(experiment.output.resolve("failure.log"))){failure.printStackTrace(new PrintWriter(log));}
        }finally{
            for(Actor actor:experiment.actors)actor.close();
            experiment.summary.put("actorsAfterShutdown",experiment.actors.stream().map(Actor::evidence).toList());
            if(experiment.actors.stream().anyMatch(a->a.process.isAlive())){experiment.summary.put("status","FAIL");experiment.instrumentationErrors.add("CHILD_SHUTDOWN_FAILED");}
            experiment.summary.put("instrumentationErrors",List.copyOf(experiment.instrumentationErrors));
            experiment.summary.put("endedAtUtc",Instant.now().toString());JSON.writeValue(experiment.output.resolve("summary.json").toFile(),experiment.summary);
        }
        System.out.println("PR20 performance "+experiment.summary.get("status")+": "+experiment.output.resolve("summary.json"));
        if(Set.of("FAIL","BLOCKED").contains(experiment.summary.get("status")))System.exit(2);
    }
    private static String safeMessage(Throwable t){String message=t.getMessage();return message==null?"No detail":message.replaceAll("(?i)(password|secret|token|authorization)[=: ][^,\\s]+","$1=[REDACTED]");}
    void run()throws Exception{
        summary.put("schemaVersion",1);summary.put("experiment","performance");summary.put("runId",output.getFileName().toString());summary.put("startedAtUtc",Instant.now().toString());
        summary.put("configuration",options.map());summary.put("fullBudget",options.full());summary.put("target",Map.of("p95Ms",300,"p99Ms",1000,"nonInjectedErrorRateExclusive",0.001,"writeRps",100,"queryRps",200));
        summary.put("hardware",Map.of("os",System.getProperty("os.name"),"osVersion",System.getProperty("os.version"),"arch",System.getProperty("os.arch"),"logicalProcessors",Runtime.getRuntime().availableProcessors(),"java",System.getProperty("java.version"),"parentJvmMaxHeapBytes",Runtime.getRuntime().maxMemory()));
        summary.put("source",source());
        summary.put("observationFailurePolicy","Ordinary observation failures retain FAIL and unavailable metrics while the frozen HTTP arrival budget continues. Disk protection and raw-evidence failures still abort. SQL, socket and resource deadlines remain unchanged.");
        String existing=commandOutput(List.of("docker","ps","--quiet"),10);summary.put("coexistingContainerCount",existing.isBlank()?0:existing.lines().count());
        summary.put("dockerResources",commandOutput(List.of("docker","info","--format","CPUs={{.NCPU}} MemoryBytes={{.MemTotal}} Root={{.DockerRootDir}}"),10));
        diskGuard(0);summary.put("provider","Durable SQL simulator; hosted payment completes immediately externally; no Stripe/bank connection");
        summary.put("historyDistribution",Map.of("count",options.history,"paymentStatus","CREATED","resolutionStatus","UNKNOWN","operationStatus","ESCALATED","recoveryStatus","ESCALATED","currency","USD","ageDays","1..365 uniformly by ordinal modulo365","amountMinor","100..1000 in100 increments; quantity1..10, unit100","origin","synthetic unresolved historical native intents; no claimed historical captures or fulfillment","immutableBindings","businessorder/payment/operation/recovery/API idempotency; complete scoped command and snapshots; alltriggers active"));
        summary.put("limitations",List.of("Synthetic history consists of unresolved permanently escalated intents, not paid history","Production bounded worker batches and default recovery delays retained; worker poll1000ms and financial poll1000ms explicitly configured","Financial production daily OVERLAP importer can complete a window before later arrivals; unresolved later BTs remain visible as correctness/completion failure","Smoke budgets cannot prove frozen throughput targets","Resource sampler queries add observed overhead; failures are retained and defeat PASS"));
        try(var db=new PostgreSQLContainer<>("postgres:16").withDatabaseName("pr20_performance_platform").withUsername("pr20_admin").withPassword("pr20_disposable_only").withInitScript("db/pr02-runtime-role.sql").withCreateContainerCmdModifier(c->c.getHostConfig().withMemory(1536L*1024*1024).withNanoCPUs(2000000000L));
            var kafka=new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.0")).withEnv("KAFKA_LISTENERS","PLAINTEXT://0.0.0.0:9092,BROKER://0.0.0.0:9093,CONTROLLER://localhost:9094").withEnv("KAFKA_HEAP_OPTS","-Xms256m -Xmx512m").withCreateContainerCmdModifier(c->c.getHostConfig().withMemory(1024L*1024*1024).withNanoCPUs(1000000000L))){
            db.start();ownedDatabase=db;diskGuard(0);kafka.start();bootstrap=kafka.getBootstrapServers();topic="pr20-notifications-"+UUID.randomUUID();
            summary.put("environment",map("postgresImage",db.getDockerImageName(),"postgresContainerId",db.getContainerId(),"database",db.getDatabaseName(),"kafkaImage",kafka.getDockerImageName(),"kafkaContainerId",kafka.getContainerId(),"providerDatabase","pr20_external_provider","apiInstances",2,"workerInstances",2,"runtimeRole","payments_app","jvmArguments",List.of("-Xms128m","-Xmx512m"),"allocation",Map.of("postgresMemoryBytes",1536L*1024*1024,"postgresNanoCpus",2000000000L,"kafkaMemoryBytes",1024L*1024*1024,"kafkaNanoCpus",1000000000L,"kafkaHeapArguments","-Xms256m -Xmx512m")));
            try(var connection=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var extConnection=providerConnection(db,connection)){
                var ds=new SingleConnectionDataSource(connection,true);admin=new JdbcTemplate(ds);external=new JdbcTemplate(new SingleConnectionDataSource(extConnection,true));
                Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).schemas("platform").defaultSchema("platform").locations("classpath:db/migration").load().migrate();
                summary.put("postgresVersionActual",admin.queryForObject("SHOW server_version",String.class));
                var kafkaVersion=kafka.execInContainer("/opt/kafka/bin/kafka-topics.sh","--version");if(kafkaVersion.getExitCode()!=0)throw new IllegalStateException("Kafka actual version observation failed");summary.put("kafkaVersionActual",kafkaVersion.getStdout().trim());
                SqlPerformanceProvider.initialize(external);
                try(var merchant=new SimulatedMerchantServer(new InetSocketAddress("127.0.0.1",0),new DriverManagerDataSource(providerUrl(db),db.getUsername(),db.getPassword()),PerformanceFixture.SECRET,Clock.systemUTC());
                    var kafkaAdmin=AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap));
                    var childCleanup=new ActorCleanup(actors)){
                    kafkaAdmin.createTopics(List.of(new NewTopic(topic,4,(short)1))).all().get(20,TimeUnit.SECONDS);
                    PerformanceFixture.seed(admin,options.history,merchant.uri().toString(),this::diskGuard);
                    diskGuard(options.history);
                    summary.put("seedDatabaseBytes",admin.queryForObject("SELECT pg_database_size(current_database())",Long.class));
                    checkHistory();
                    admin=observations(db.getJdbcUrl(),db.getUsername(),db.getPassword(),"platform");
                    external=observations(providerUrl(db),db.getUsername(),db.getPassword(),"external");
                    queryIds.addAll(admin.queryForList("SELECT id FROM platform.payment ORDER BY created_at DESC LIMIT 1000",UUID.class).stream().map(id->PaymentIds.format("pay",id)).toList());
                    if(queryIds.isEmpty())throw new IllegalArgumentException("History must contain at least1 queryable payment");
                    for(int n=0;n<4;n++)actors.add(startActor(db,merchant.uri().toString(),n));
                    for(Actor actor:actors)actor.awaitReady();
                    if(actors.stream().map(a->a.process.pid()).distinct().count()!=4||actors.stream().anyMatch(a->!a.process.isAlive()))throw new IllegalStateException("Four distinct live application JVMs required");
                    summary.put("fourDistinctLiveJvmsVerified",true);
                    checkHistory();if(external.queryForObject("SELECT count(*) FROM simulator_payment",Long.class)!=0)throw new IllegalStateException("Historical L2 scan resent an external command");
                    summary.put("historicalL2EligibilityVerified",true);
                    summary.put("roleReadiness",actors.stream().map(a->{try{return JSON.readTree(a.control.resolve("ready.json").toFile());}catch(IOException e){throw new UncheckedIOException(e);}}).toList());
                    summary.put("actors",actors.stream().map(Actor::evidence).toList());
                    summary.put("databaseSettings",admin.queryForList("SELECT name,setting,unit FROM pg_settings WHERE name IN ('max_connections','shared_buffers','work_mem','maintenance_work_mem','wal_buffers','max_wal_size','fsync','synchronous_commit') ORDER BY name"));
                    historicalCutoff=admin.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
                    long historicalUnknown=historyUnknownCount(admin,historicalCutoff);
                    if(historicalUnknown!=options.history)throw new IllegalStateException("Historical database-time cohort incomplete");
                    summary.put("historicalObservationCohort",Map.of("databaseExpiresAtBefore",historicalCutoff.toString(),"verifiedUnknownCount",historicalUnknown,"definition","Pre-generator historical deadlines are expired before this measured database cutoff; new request deadlines fall after it. Payment identity guards forbid changing deadlines. Counts are measured live, not cached."));
                    loadStart=Instant.now();summary.put("loadStartedAtUtc",loadStart.toString());
                    // Observations use bounded independent connections; the generator only makes production HTTP calls.
                    Thread sampler=Thread.ofPlatform().daemon(true).name("pr20-resource-sampler").start(()->sampleLoop(kafkaAdmin,db.getContainerId(),kafka.getContainerId()));
                    Phase warmup,measure;
                    try(var raw=Files.newBufferedWriter(output.resolve("requests.csv"));var executor=Executors.newVirtualThreadPerTaskExecutor()){
                        raw.write("phase,kind,ordinal,scheduledEpochMillis,scheduledNanos,actualStartNanos,finishedNanos,queueDelayMs,latencyMs,status,outcome,resourceId\n");raw.flush();
                        warmup=phase("warmup",options.warmup,executor,raw,kafkaAdmin);
                        var before=observeState(kafkaAdmin,"measurement-start");summary.put("measurementStartedAtUtc",Instant.now().toString());
                        measure=phase("measurement",options.measure,executor,raw,kafkaAdmin);
                        var end=new LinkedHashMap<>(measure.windowEnd);
                        summary.put("measurementEndedAtUtc",end.get("atUtc"));
                        summary.put("rawEvidence",Map.of("requestsCsv",output.resolve("requests.csv").toString(),"samplesNdjson",output.resolve("samples.ndjson").toString(),"diskGuardsNdjson",output.resolve("disk-guards.ndjson").toString(),"drainNdjson",output.resolve("drain.ndjson").toString()));
                        summary.put("warmup",warmup.map());summary.put("measurement",measure.map());summary.put("measurementBefore",before);summary.put("measurementEnd",end);
                        boolean boundariesComplete=Boolean.TRUE.equals(before.get("observationComplete"))&&Boolean.TRUE.equals(end.get("observationComplete"));
                        summary.put("measurementBoundariesComplete",boundariesComplete);
                        summary.put("completedBusinessThroughputRps",boundariesComplete?Math.max(0,number(end,"businessCompleted")-number(before,"businessCompleted"))/(double)options.measure:null);
                        summary.put("acceptedThroughputRps",measure.writesAccepted.get()/(double)options.measure);
                        summary.put("counterDefinitions",Map.of("acceptance","Successful HTTP write responses for measurement arrival cohort divided by1800s (or configured smoke period); completion waiting separately timed","completedBusiness","Intersection of local SUCCEEDED+POSTED payment IDs and external durable merchant fulfillment IDs at measurementwindowboundary; delta divided bymeasurementseconds","queueGrowth","Sum of positive deltas for each individual operational work queue and Kafka lag at measurement boundary; one queue draining cannot cancel another growing, and later drain cannot erase growth","providerAccepted","Distinct durable external simulator scopedoperation-key rows","providerCaptureEffects","Exactlyone immediate captured effect per simulatorrow; explicit duplicateperpayment check","refundEffects","0; workloaddoesnot exercise refunds"));
                        summary.put("fullArrivalBudgetObserved",options.full()&&warmup.actualSeconds>=300&&measure.actualSeconds>=1800&&warmup.scheduled.get()==90000&&measure.scheduled.get()==540000);
                        Long growth=null;
                        if(boundariesComplete){
                            var beforeQueues=queueCounts(before);var endQueues=queueCounts(end);
                            growth=PerformancePolicy.positiveQueueGrowth(beforeQueues,endQueues);
                            var perQueueGrowth=new LinkedHashMap<String,Long>();for(var key:endQueues.keySet())perQueueGrowth.put(key,Math.max(0,endQueues.get(key)-beforeQueues.getOrDefault(key,0L)));summary.put("measurementQueueGrowthByQueue",perQueueGrowth);
                        }
                        summary.put("measurementQueueGrowth",growth);
                        long drainStart=System.nanoTime();Instant drainAt=Instant.now();summary.put("drainStartedAtUtc",drainAt.toString());
                        try(var observations=Files.newBufferedWriter(output.resolve("drain.ndjson"))){
                            while(true){
                                if(abortReason.get()!=null)throw new IllegalStateException(abortReason.get());
                                diskGuard(-1);
                                var fresh=observeState(kafkaAdmin,"drain");fresh.put("outstandingRequests",outstanding.get());
                                observations.write(JSON.writeValueAsString(fresh));observations.newLine();observations.flush();summary.put("drainLastObservation",fresh);
                                boolean drained=Boolean.TRUE.equals(fresh.get("observationComplete"))&&PerformancePolicy.drained(number(fresh,"totalQueue"),number(fresh,"kafkaLag"),((Number)fresh.get("outstandingRequests")).intValue());summary.put("drainCompleted",drained);
                                if(drained||System.nanoTime()-drainStart>=TimeUnit.SECONDS.toNanos(options.drain))break;Thread.sleep(1000);
                            }
                        }
                        summary.put("drainSecondsActual",(System.nanoTime()-drainStart)/1e9);
                        samplerStop.set(true);sampler.join(15000);if(sampler.isAlive())throw new IllegalStateException("Sampler failed to stop");
                        var correctness=observed("correctness",this::correctness);summary.put("correctness",correctness);summary.put("resourceSampleCount",samples.size());
                        var broker=observed("final-broker",()->map("kafkaLag",kafkaLag(kafkaAdmin)));summary.put("finalKafkaLag",broker.get("kafkaLag"));summary.put("fourDistinctLiveJvmsAtMeasurementEnd",measure.liveActorsAtWindowEnd);summary.put("instrumentationErrors",List.copyOf(instrumentationErrors));
                        boolean incorrect=!measure.liveActorsAtWindowEnd||!Boolean.TRUE.equals(summary.get("drainCompleted"))||!boundariesComplete||!Boolean.TRUE.equals(broker.get("observationComplete"))||!Boolean.TRUE.equals(correctness.get("pass"))||!instrumentationErrors.isEmpty()||measure.writesAccepted.get()==0||measure.queriesOk.get()==0||!actors.stream().allMatch(a->a.process.isAlive())||number(summary,"finalKafkaLag")!=0;
                        double p95=Math.max(measure.percentile("write",95),measure.percentile("query",95));double p99=Math.max(measure.percentile("write",99),measure.percentile("query",99));
                        summary.put("fullBudgetObserved",Boolean.TRUE.equals(summary.get("fullArrivalBudgetObserved"))&&boundariesComplete&&Boolean.TRUE.equals(correctness.get("observationComplete"))&&instrumentationErrors.isEmpty());
                        summary.put("status",PerformancePolicy.verdict(options.full(),p95,p99,measure.errors.get()/(double)Math.max(1,measure.attempted.get()),measure.drops.get(),growth==null?0:growth,incorrect));
                    }finally{samplerStop.set(true);sampler.interrupt();sampler.join(15000);if(sampler.isAlive()){instrumentationErrors.add("SAMPLER_SHUTDOWN_TIMEOUT");throw new IllegalStateException("Sampler shutdown timeout");}}
                }
            }
        }
    }
    JdbcTemplate observations(String url,String user,String password,String database){
        var source=new DriverManagerDataSource(url,user,password);var properties=new java.util.Properties();properties.setProperty("connectTimeout","5");properties.setProperty("socketTimeout","10");source.setConnectionProperties(properties);var jdbc=new ObservedJdbcTemplate(source,output.resolve("query-observations.ndjson"),database,failure->abortReason.compareAndSet(null,"QUERY_DIAGNOSTIC_WRITE_FAILED"));jdbc.setQueryTimeout(10);return jdbc;
    }
    static Connection providerConnection(PostgreSQLContainer<?> db,Connection admin)throws SQLException{
        try(var statement=admin.createStatement()){statement.execute("CREATE DATABASE pr20_external_provider");}
        return DriverManager.getConnection(providerUrl(db),db.getUsername(),db.getPassword());
    }
    static String providerUrl(PostgreSQLContainer<?> db){return db.getJdbcUrl().replace("/"+db.getDatabaseName(),"/pr20_external_provider");}
    void checkHistory(){
        long count=admin.queryForObject("SELECT count(*) FROM platform.payment",Long.class);
        long valid=admin.queryForObject("SELECT count(*) FROM platform.payment p JOIN platform.provider_operation o ON o.payment_id=p.id JOIN platform.recovery_task t ON t.operation_id=o.id JOIN platform.api_idempotency_record a ON a.operation_id=o.id WHERE p.resolution_status='UNKNOWN' AND o.status='ESCALATED' AND t.status='ESCALATED'",Long.class);
        if(count!=options.history||valid!=count)throw new IllegalStateException("Historical scoped associations incomplete");
        summary.put("seedVerification",Map.of("paymentCount",count,"fullyAssociatedEscalatedCount",valid,"outboxCount",admin.queryForObject("SELECT count(*) FROM platform.outbox_event",Long.class),"constraintsAndTriggersEnabled",true));
    }
    Actor startActor(PostgreSQLContainer<?> db,String merchant,int n)throws IOException{
        String role=n<2?"api":"worker",name=role+"-"+(n%2+1);Path control=output.resolve(name);Files.createDirectory(control);
        int port;try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
        List<String> command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xms128m","-Xmx512m","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),Pr20PerformanceChild.class.getName(),"--spring.profiles.active="+role+",pr20-performance","--server.address=127.0.0.1","--server.port="+port,"--spring.datasource.url="+db.getJdbcUrl(),"--spring.datasource.username=payments_app","--spring.flyway.enabled=false","--platform.provider.fake.enabled=true","--platform.messaging.enabled=true","--platform.messaging.topic="+topic,"--spring.kafka.bootstrap-servers="+bootstrap,"--platform.financial-ingestion.enabled=true","--platform.financial-ingestion.organization-id="+PerformanceFixture.ORG,"--platform.financial-ingestion.environment-id="+PerformanceFixture.ENV,"--platform.financial-ingestion.provider-account-id="+PerformanceFixture.PROVIDER,"--platform.financial-ingestion.poll-ms=1000","--platform.recovery.poll-ms=1000","--platform.notifications.poll-ms=1000","--logging.level.root=WARN","--logging.level.com.paymentsledger=WARN"));
        var builder=new ProcessBuilder(command);builder.environment().clear();
        // Only controlled test configuration is inherited; no shell credential/.env loading.
        builder.environment().putAll(Map.of("PATH",System.getenv().getOrDefault("PATH","/usr/bin:/bin"),"PR20_ROLE",role,"PR20_CONTROL",control.toString(),"PR20_PROVIDER_URL",providerUrl(db),"PR20_PROVIDER_USER",db.getUsername(),"PR20_PROVIDER_PASSWORD",db.getPassword(),"PR20_MERCHANT_URI",merchant,"PR20_MERCHANT_SECRET",PerformanceFixture.SECRET,"SPRING_DATASOURCE_PASSWORD","pr02_test_only"));
        builder.redirectErrorStream(true).redirectOutput(control.resolve("application.log").toFile());return new Actor(name,port,control,builder.start(),new AtomicBoolean());
    }
    record ActorCleanup(List<Actor> actors)implements AutoCloseable{public void close(){for(Actor actor:actors)actor.close();}}
    record Actor(String name,int port,Path control,Process process,AtomicBoolean forceKilled)implements AutoCloseable{
        void awaitReady()throws Exception{
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
            while(!Files.exists(control.resolve("ready.json"))){if(!process.isAlive())throw new IllegalStateException("Application actor exited: "+name+"; see application.log");if(System.nanoTime()>until)throw new IllegalStateException("Application actor startup timeout: "+name);Thread.sleep(200);}
        }
        Map<String,Object> evidence(){return map("name",name,"pid",process.pid(),"port",port,"directory",control.toString(),"alive",process.isAlive(),"forceKilled",forceKilled.get(),"exitCode",process.isAlive()?null:process.exitValue());}
        @Override public void close(){if(process.isAlive()){process.destroy();try{if(!process.waitFor(15,TimeUnit.SECONDS)){forceKilled.set(true);process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}catch(InterruptedException e){process.destroyForcibly();Thread.currentThread().interrupt();}}}
    }
    Phase phase(String name,int seconds,ExecutorService executor,BufferedWriter raw,AdminClient kafka)throws Exception{
        var phase=new Phase(name,seconds);activePhase=phase;long start=System.nanoTime(),epoch=System.currentTimeMillis();
        Thread writes=Thread.ofPlatform().start(()->arrivals(phase,"write",options.writeRps,seconds,start,epoch,executor,raw));
        Thread queries=Thread.ofPlatform().start(()->arrivals(phase,"query",options.queryRps,seconds,start,epoch,executor,raw));
        writes.join();queries.join();if(abortReason.get()!=null)throw new IllegalStateException(abortReason.get());
        long phaseUntil=start+TimeUnit.SECONDS.toNanos(seconds);while(System.nanoTime()<phaseUntil)LockSupport.parkNanos(Math.min(1000000,phaseUntil-System.nanoTime()));
        phase.actualSeconds=(System.nanoTime()-start)/1e9;phase.liveActorsAtWindowEnd=actors.stream().allMatch(a->a.process.isAlive());phase.windowEnd=observeState(kafka,name+"-boundary");
        long completionWaitStarted=System.nanoTime(),deadline=completionWaitStarted+TimeUnit.SECONDS.toNanos(30);
        while(phase.inFlight.get()>0&&System.nanoTime()<deadline)Thread.sleep(25);
        if(phase.inFlight.get()!=0)throw new IllegalStateException("Phase has unrecorded in-flight outcomes");
        phase.completionWaitSeconds=(System.nanoTime()-completionWaitStarted)/1e9;
        synchronized(raw){raw.flush();}return phase;
    }
    void arrivals(Phase phase,String kind,int rate,int seconds,long start,long epoch,ExecutorService executor,BufferedWriter raw){
        for(long n=0;n<(long)rate*seconds;n++){
            if(abortReason.get()!=null)break;
            long offset=PerformancePolicy.scheduledNanos(n,rate),scheduled=start+offset;
            while(System.nanoTime()<scheduled)LockSupport.parkNanos(Math.min(1000000,scheduled-System.nanoTime()));
            long id=ordinal.incrementAndGet();phase.scheduled.incrementAndGet();(kind.equals("write")?phase.scheduledWrites:phase.scheduledQueries).incrementAndGet();
            boolean late=System.nanoTime()-scheduled>TimeUnit.SECONDS.toNanos(1);
            int current=late?0:outstanding.incrementAndGet();
            if(late||current>384){
                if(!late)outstanding.decrementAndGet();phase.drops.incrementAndGet();writeRaw(raw,phase.name,kind,id,epoch+offset/1000000,scheduled,0,0,0,0,0,"DROPPED","");continue;
            }
            phase.inFlight.incrementAndGet();
            executor.submit(()->{long actual=System.nanoTime();int status=0;String outcome="ERROR",resource="";
                try{
                    int api=(int)(id%2);HttpRequest.Builder request;
                    if(kind.equals("write")){
                        String key="pr20-load-"+options.seed+"-"+id;
                        String body=JSON.writeValueAsString(Map.of("orderKey",key,"productRef","pr20-product","priceVersion",1,"amountMinor",100,"currency","USD","successUrl","https://merchant.test/ok","cancelUrl","https://merchant.test/cancel"));
                        request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+actors.get(api).port+"/v1/payments")).header("Content-Type","application/json").header("Idempotency-Key",key).POST(HttpRequest.BodyPublishers.ofString(body));
                    }else{
                        synchronized(queryIds){resource=queryIds.get((int)Math.floorMod(id*31+options.seed,queryIds.size()));}
                        request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+actors.get(api).port+"/v1/payments/"+resource)).GET();
                    }
                    request.timeout(Duration.ofSeconds(20)).header("Authorization","Bearer app_"+PerformanceFixture.CREDENTIAL+"."+PerformanceFixture.SECRET);
                    var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());status=response.statusCode();
                    if(status>=200&&status<300){
                        outcome="OK";if(kind.equals("write")){resource=JSON.readTree(response.body()).path("id").asText();if(resource.isEmpty())throw new IllegalStateException("Accepted response missing id");queryIds.add(resource);phase.writesAccepted.incrementAndGet();}else phase.queriesOk.incrementAndGet();
                    }
                }catch(Exception e){outcome=e.getClass().getSimpleName();}
                finally{long finish=System.nanoTime();phase.attempted.incrementAndGet();(kind.equals("write")?phase.attemptedWrites:phase.attemptedQueries).incrementAndGet();if(!outcome.equals("OK"))phase.errors.incrementAndGet();phase.record(kind,finish-actual);writeRaw(raw,phase.name,kind,id,epoch+offset/1000000,scheduled,actual,finish,(actual-scheduled)/1e6,(finish-actual)/1e6,status,outcome,resource);phase.inFlight.decrementAndGet();outstanding.decrementAndGet();}
            });
        }
    }
    void writeRaw(BufferedWriter raw,String phase,String kind,long id,long epoch,long scheduled,long actual,long finish,double delay,double latency,int status,String outcome,String resource){
        try{synchronized(raw){raw.write(phase+","+kind+","+id+","+epoch+","+scheduled+","+actual+","+finish+","+delay+","+latency+","+status+","+outcome+","+resource+"\n");}}catch(IOException e){abortReason.compareAndSet(null,"RAW_CSV_WRITE_FAILED");try{observationFailure(new LinkedHashMap<>(),"raw-csv",e);}catch(IOException unavailable){e.addSuppressed(unavailable);instrumentationErrors.add("RAW_CSV_WRITE_FAILED");}}
    }
    static final class Phase{
        final String name;final int seconds;double actualSeconds,completionWaitSeconds;boolean liveActorsAtWindowEnd;Map<String,Object> windowEnd=Map.of();final AtomicLong scheduledWrites=new AtomicLong(),scheduledQueries=new AtomicLong(),attemptedWrites=new AtomicLong(),attemptedQueries=new AtomicLong();final AtomicLong scheduled=new AtomicLong(),attempted=new AtomicLong(),errors=new AtomicLong(),drops=new AtomicLong(),writesAccepted=new AtomicLong(),queriesOk=new AtomicLong();final AtomicInteger inFlight=new AtomicInteger();
        final List<Long> write=Collections.synchronizedList(new ArrayList<>()),query=Collections.synchronizedList(new ArrayList<>());
        Phase(String name,int seconds){this.name=name;this.seconds=seconds;}
        void record(String kind,long nanos){(kind.equals("write")?write:query).add(nanos);}
        double percentile(String kind,int percent){List<Long> values=kind.equals("write")?write:query; synchronized(values){return PerformancePolicy.percentile(values.stream().mapToLong(Long::longValue).toArray(),percent)/1e6;}}
        Map<String,Object> map(){return Pr20PerformanceExperiment.map("seconds",seconds,"actualSeconds",actualSeconds,"completionWaitSeconds",completionWaitSeconds,"scheduledWrites",scheduledWrites.get(),"scheduledQueries",scheduledQueries.get(),"attemptedWrites",attemptedWrites.get(),"attemptedQueries",attemptedQueries.get(),"effectiveWriteArrivalRps",attemptedWrites.get()/(double)Math.max(1,seconds),"effectiveQueryArrivalRps",attemptedQueries.get()/(double)Math.max(1,seconds),"scheduled",scheduled.get(),"attempted",attempted.get(),"errors",errors.get(),"errorRate",errors.get()/(double)Math.max(1,attempted.get()),"dropped",drops.get(),"writesAccepted",writesAccepted.get(),"queriesOk",queriesOk.get(),"latencyMs",Map.of("write",Map.of("p50",percentile("write",50),"p95",percentile("write",95),"p99",percentile("write",99)),"query",Map.of("p50",percentile("query",50),"p95",percentile("query",95),"p99",percentile("query",99))));}
    }
    void sampleLoop(AdminClient kafka,String dbContainer,String kafkaContainer){
        try(var raw=Files.newBufferedWriter(output.resolve("samples.ndjson"))){
            do{
                var sample=new LinkedHashMap<String,Object>();sample.put("atUtc",Instant.now().toString());
                try{
                    diskGuard(-1);
                    sample.putAll(localState());sample.put("db",admin.queryForMap("SELECT numbackends,xact_commit,xact_rollback,blks_read,blks_hit,tup_returned,tup_fetched,tup_inserted,tup_updated,deadlocks,temp_bytes,pg_database_size(datname) database_bytes FROM pg_stat_database WHERE datname=current_database()"));
                    sample.put("dbActivity",admin.queryForList("SELECT state,wait_event_type,wait_event,count(*) sessions FROM pg_stat_activity WHERE datname=current_database() GROUP BY state,wait_event_type,wait_event"));
                    sample.put("provider",external.queryForMap("SELECT (SELECT count(*) FROM simulator_call) calls,(SELECT count(*) FROM simulator_payment) accepted,(SELECT count(*) FROM simulator_payment) capture_effects"));
                    var committed=kafka.listConsumerGroupOffsets(ConsumerInboxService.CONSUMER_NAME).partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS);
                    var partitions=new ArrayList<TopicPartition>();for(int p=0;p<4;p++)partitions.add(new TopicPartition(topic,p));
                    Map<TopicPartition,OffsetSpec> offsets=new HashMap<>();for(var p:partitions)offsets.put(p,OffsetSpec.latest());
                    var latest=kafka.listOffsets(offsets).all().get(5,TimeUnit.SECONDS);long lag=0;var detail=new LinkedHashMap<String,Object>();
                    for(var p:partitions){long end=latest.get(p).offset(),current=committed.containsKey(p)?committed.get(p).offset():0;lag+=Math.max(0,end-current);detail.put(p.toString(),Map.of("end",end,"committed",current));}
                    sample.put("kafkaLag",lag);sample.put("kafkaOffsets",detail);
                    var stats=new ProcessBuilder("docker","stats","--no-stream","--format","{{json .}}",dbContainer,kafkaContainer).redirectErrorStream(true).start();
                    if(!stats.waitFor(5,TimeUnit.SECONDS)){stats.destroyForcibly();throw new IllegalStateException("Container resource timeout");}
                    if(stats.exitValue()!=0)throw new IllegalStateException("Container resource unavailable");sample.put("containerResources",new String(stats.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));sample.put("observationComplete",true);
                }catch(Exception e){observationFailure(sample,"resource-sample",e);if(abortReason.get()!=null){sample.put("abortReason",abortReason.get());samplerStop.set(true);}}
                samples.add(sample);raw.write(JSON.writeValueAsString(sample));raw.newLine();raw.flush();
                for(int n=0;n<50&&!samplerStop.get();n++)Thread.sleep(100);
            }while(!samplerStop.get());
        }catch(Exception e){instrumentationErrors.add("SAMPLER_FAILED_"+e.getClass().getSimpleName());abortReason.compareAndSet(null,"SAMPLER_FAILED");}
    }
    @FunctionalInterface interface Observation {Map<String,Object> read()throws Exception;}
    Map<String,Object> observed(String context,Observation observation)throws IOException{
        var result=new LinkedHashMap<String,Object>();result.put("atUtc",Instant.now().toString());
        try{result.putAll(observation.read());result.put("observationComplete",true);}
        catch(Exception failure){observationFailure(result,context,failure);}
        result.put("observationCompletedAtUtc",Instant.now().toString());return result;
    }
    Map<String,Object> observeState(AdminClient kafka,String context)throws IOException{
        return observed(context,()->{var state=localState();state.put("kafkaLag",kafkaLag(kafka));return state;});
    }
    void observationFailure(Map<String,Object> result,String context,Exception failure)throws IOException{
        result.put("observationComplete",false);result.put("instrumentationError",failure.getClass().getSimpleName());
        instrumentationErrors.add(context+"_"+failure.getClass().getSimpleName());
        var record=new LinkedHashMap<String,Object>();record.put("atUtc",Instant.now().toString());record.put("context",context);
        var trace=new StringWriter();failure.printStackTrace(new PrintWriter(trace));record.put("failure",trace.toString());
        synchronized(this){Files.writeString(output.resolve("observation-failures.ndjson"),JSON.writeValueAsString(record)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
    }
    long kafkaLag(AdminClient kafka)throws Exception{
        var committed=kafka.listConsumerGroupOffsets(ConsumerInboxService.CONSUMER_NAME).partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS);Map<TopicPartition,OffsetSpec> offsets=new HashMap<>();for(int p=0;p<4;p++)offsets.put(new TopicPartition(topic,p),OffsetSpec.latest());var end=kafka.listOffsets(offsets).all().get(5,TimeUnit.SECONDS);long lag=0;for(var entry:end.entrySet())lag+=Math.max(0,entry.getValue().offset()-(committed.containsKey(entry.getKey())?committed.get(entry.getKey()).offset():0));return lag;
    }
    static long historyUnknownCount(JdbcTemplate db,Instant cutoff){return number(paymentObservations(db,cutoff),"historyUnknown");}
    static Map<String,Object> paymentObservations(JdbcTemplate db,Instant cutoff){
        Objects.requireNonNull(cutoff,"A measured database cohort cutoff is required");
        String before="expires_at < TIMESTAMPTZ '"+cutoff+"'",after="expires_at >= TIMESTAMPTZ '"+cutoff+"'";
        // A single live MVCC snapshot; only the owned fixture scope is included.
        // The seed is fully validated before capturing this cutoff and starting any new request.
        return db.queryForMap("SELECT count(*) FILTER (WHERE "+before+" AND resolution_status='UNKNOWN') AS \"historyUnknown\","
                +"count(*) FILTER (WHERE "+after+" AND resolution_status<>'KNOWN') AS \"currentLoadUnknown\","
                +"count(*) FILTER (WHERE payment_status='SUCCEEDED') AS \"localSucceeded\","
                +"count(*) FILTER (WHERE payment_status='SUCCEEDED' AND financial_status='POSTED') AS \"ledgerPostedPayments\","
                +"count(*) FILTER (WHERE "+after+" AND (payment_status<>'SUCCEEDED' OR financial_status<>'POSTED')) AS \"uncompletedLoad\","
                +"coalesce(extract(epoch FROM now()-min(unresolved_since) FILTER (WHERE "+after+" AND resolution_status<>'KNOWN')),0) AS \"unknownAgeSeconds\""
                +" FROM platform.payment WHERE organization_id='"+PerformanceFixture.ORG+"'::uuid AND environment_id='"+PerformanceFixture.ENV+"'::uuid"
                +" AND application_id='"+PerformanceFixture.APP+"'::uuid AND provider_account_id='"+PerformanceFixture.PROVIDER+"'::uuid AND currency='USD'");
    }
    Map<String,Object> localState(){
        var result=new LinkedHashMap<String,Object>();
        var payments=paymentObservations(admin,historicalCutoff);
        for(String key:List.of("historyUnknown","currentLoadUnknown","localSucceeded","ledgerPostedPayments"))result.put(key,number(payments,key));
        long fulfilled=external.queryForObject("SELECT count(*) FROM merchant_fixture.fulfillment",Long.class);
        result.put("merchantFulfilled",fulfilled);
        var postedIds=new HashSet<>(admin.queryForList("SELECT id FROM platform.payment WHERE payment_status='SUCCEEDED' AND financial_status='POSTED'",UUID.class));
        long completed=external.queryForList("SELECT payment_id FROM merchant_fixture.fulfillment",UUID.class).stream().filter(postedIds::contains).count();
        result.put("businessCompleted",completed);
        long total=0;var queues=new LinkedHashMap<String,Object>();
        for(String definition:List.of("outbox_event|status<>'PUBLISHED'|occurred_at","delivery_task|status IN ('READY','RUNNING','HELD')|created_at","recovery_task|status IN ('READY','WAITING','RUNNING')|created_at","balance_transaction|state<>'POSTED'|created_at")){
            String[] parts=definition.split("\\|");var q=admin.queryForMap("SELECT count(*) count,coalesce(extract(epoch FROM now()-min("+parts[2]+")),0) oldestAgeSeconds FROM platform."+parts[0]+" WHERE "+parts[1]);queues.put(parts[0],q);total+=number(q,"count");
        }
        long unposted=number(payments,"uncompletedLoad");
        queues.put("uncompletedLoad",unposted);total+=unposted;result.put("totalQueue",total);result.put("queues",queues);
        result.put("unknownAgeSeconds",((Number)payments.get("unknownAgeSeconds")).doubleValue());
        result.put("recoveryLeaseAgeSeconds",admin.queryForObject("SELECT greatest(0,coalesce(max(extract(epoch FROM now()-updated_at)),0)) FROM platform.recovery_task WHERE status='RUNNING' AND lease_until IS NOT NULL",Double.class));
        result.put("recoveryLeaseExpiredAgeSeconds",admin.queryForObject("SELECT greatest(0,coalesce(max(extract(epoch FROM now()-lease_until)),0)) FROM platform.recovery_task WHERE lease_until IS NOT NULL",Double.class));
        result.put("journals",admin.queryForObject("SELECT count(*) FROM platform.journal",Long.class));result.put("financialEvents",admin.queryForObject("SELECT count(*) FROM platform.financial_event",Long.class));result.put("outboxEvents",admin.queryForObject("SELECT count(*) FROM platform.outbox_event",Long.class));
        return result;
    }
    Map<String,Object> correctness(){
        var result=new LinkedHashMap<String,Object>();result.putAll(localState());
        long externalEffects=external.queryForObject("SELECT count(*) FROM simulator_payment",Long.class);result.put("providerCaptured",externalEffects);
        long externalDuplicates=external.queryForObject("SELECT count(*) FROM (SELECT metadata->>'payment_id' FROM simulator_payment GROUP BY metadata->>'payment_id' HAVING count(*)>1) x",Long.class);result.put("duplicateExternalCapturesPerPayment",externalDuplicates);
        long trial=admin.queryForObject("SELECT count(*) FROM (SELECT journal_id FROM platform.journal_entry GROUP BY journal_id HAVING sum(CASE side WHEN 'DEBIT' THEN amount_minor::numeric ELSE -amount_minor::numeric END)<>0) x",Long.class);
        long projection=admin.queryForObject("SELECT count(*) FROM (SELECT a.id,coalesce(sum(CASE e.side WHEN 'DEBIT' THEN e.amount_minor::numeric ELSE -e.amount_minor::numeric END),0) actual,coalesce(p.balance_minor,0) cached FROM platform.ledger_account a LEFT JOIN platform.journal_entry e ON e.ledger_account_id=a.id LEFT JOIN platform.balance_projection p ON p.ledger_account_id=a.id GROUP BY a.id,p.balance_minor HAVING coalesce(sum(CASE e.side WHEN 'DEBIT' THEN e.amount_minor::numeric ELSE -e.amount_minor::numeric END),0)<>coalesce(p.balance_minor,0)) x",Long.class);
        long duplicate=admin.queryForObject("SELECT count(*) FROM (SELECT financial_event_id FROM platform.journal GROUP BY financial_event_id HAVING count(*)>1 UNION ALL SELECT id FROM platform.journal WHERE sealed_at IS NULL) x",Long.class);
        long overrefund=admin.queryForObject("SELECT count(*) FROM platform.payment p WHERE coalesce((SELECT sum(r.amount_minor) FROM platform.refund r WHERE r.payment_id=p.id AND r.refund_status IN ('SUCCEEDED','RESERVED','SUBMITTED','PENDING','REQUIRES_ACTION')),0)>p.captured_amount_minor",Long.class);
        long historyResent=admin.queryForObject("SELECT count(*) FROM platform.provider_operation o JOIN platform.business_order b ON b.id=(SELECT business_order_id FROM platform.payment WHERE id=o.payment_id) WHERE b.order_key LIKE 'pr20-history-%' AND (o.status<>'ESCALATED' OR o.attempt_count<>1)",Long.class);
        var providerPayments=new HashSet<>(external.queryForList("SELECT (metadata->>'payment_id')::uuid FROM simulator_payment",UUID.class));
        var localPosted=new HashSet<>(admin.queryForList("SELECT id FROM platform.payment WHERE payment_status='SUCCEEDED' AND financial_status='POSTED'",UUID.class));
        var fulfilledPayments=new HashSet<>(external.queryForList("SELECT payment_id FROM merchant_fixture.fulfillment",UUID.class));
        long missingLocal=providerPayments.stream().filter(id->!localPosted.contains(id)).count(),unexpectedLocal=localPosted.stream().filter(id->!providerPayments.contains(id)).count();
        long missingFulfillment=providerPayments.stream().filter(id->!fulfilledPayments.contains(id)).count(),unexpectedFulfillment=fulfilledPayments.stream().filter(id->!providerPayments.contains(id)).count();
        result.put("missingLocalPostedEffects",missingLocal);result.put("unexpectedLocalPostedEffects",unexpectedLocal);result.put("missingMerchantEffects",missingFulfillment);result.put("unexpectedMerchantEffects",unexpectedFulfillment);
        result.put("coverage",Map.of("captureEffects",externalEffects,"journalEntryCount",admin.queryForObject("SELECT count(*) FROM platform.journal_entry",Long.class),"accountProjectionCount",admin.queryForObject("SELECT count(*) FROM platform.balance_projection",Long.class),"refundEffects",0,"refundCheck","zero-refund workload checks capacity invariant; no refund operation exercised","recoveryLeaseCheck","sampled max lease ages; no injected recovery fault in performance"));
        result.put("trialBalanceFailures",trial);result.put("independentProjectionDifferences",projection);result.put("duplicateOrUnsealedJournals",duplicate);result.put("overRefundPayments",overrefund);result.put("historyResent",historyResent);
        result.put("providerCallsByKind",external.queryForList("SELECT kind,count(*) calls FROM simulator_call GROUP BY kind ORDER BY kind"));
        result.put("providerAmountMinor",external.queryForObject("SELECT coalesce(sum(amount),0) FROM simulator_payment",Long.class));
        result.put("providerAvailableMinor",number(result,"providerAmountMinor"));
        result.put("independentPspFundsMinor",admin.queryForObject("SELECT coalesce(sum(CASE e.side WHEN 'DEBIT' THEN e.amount_minor::numeric ELSE -e.amount_minor::numeric END),0) FROM platform.journal_entry e JOIN platform.ledger_account a ON a.id=e.ledger_account_id WHERE a.account_purpose='PSP_FUNDS' AND a.provider_account_id='"+PerformanceFixture.PROVIDER+"'",Long.class));
        result.put("providerBalanceDifferenceMinor",number(result,"independentPspFundsMinor")-number(result,"providerAvailableMinor"));
        result.put("financialEventTypes",admin.queryForList("SELECT event_type,count(*) count FROM platform.financial_event GROUP BY event_type ORDER BY event_type"));
        result.put("ledgerCaptureAmountMinor",admin.queryForObject("SELECT coalesce(sum(captured_amount_minor),0) FROM platform.payment WHERE payment_status='SUCCEEDED'",Long.class));
        result.put("pass",number(result,"providerBalanceDifferenceMinor")==0&&externalDuplicates==0&&missingLocal==0&&unexpectedLocal==0&&missingFulfillment==0&&unexpectedFulfillment==0&&externalEffects>0&&trial==0&&projection==0&&duplicate==0&&overrefund==0&&historyResent==0&&number(result,"currentLoadUnknown")==0&&number(result,"ledgerPostedPayments")==externalEffects&&number(result,"merchantFulfilled")==externalEffects&&number(result,"totalQueue")==0&&number(result,"providerAmountMinor")==number(result,"ledgerCaptureAmountMinor"));
        return result;
    }
    @SuppressWarnings("unchecked") static Map<String,Long> queueCounts(Map<String,Object> sample){
        var result=new LinkedHashMap<String,Long>();var queues=(Map<String,Object>)sample.get("queues");
        if(queues==null)throw new IllegalArgumentException("Queue observations required");
        for(var entry:queues.entrySet()){Object count=entry.getValue();result.put(entry.getKey(),count instanceof Number n?n.longValue():number((Map<String,Object>)count,"count"));}
        if(!(sample.get("kafkaLag") instanceof Number))throw new IllegalArgumentException("Fresh Kafka lag observation required");
        result.put("kafkaLag",number(sample,"kafkaLag"));return result;
    }
    static Map<String,Object> map(Object...pairs){var result=new LinkedHashMap<String,Object>();for(int n=0;n<pairs.length;n+=2)result.put((String)pairs[n],pairs[n+1]);return result;}
    static long number(Map<String,?> map,String key){Object value=map.get(key);return value instanceof Number n?n.longValue():0;}
    synchronized void diskGuard(long seeded){
        try{
            var store=Files.getFileStore(output);long usable=store.getUsableSpace();
            var record=new LinkedHashMap<String,Object>();record.put("atUtc",Instant.now().toString());record.put("historySeeded",seeded);record.put("hostUsableBytes",usable);record.put("hostTotalBytes",store.getTotalSpace());record.put("reserveBytes",2147483648L);
            long dockerUsable=Long.MAX_VALUE;
            if(ownedDatabase!=null){var df=ownedDatabase.execInContainer("df","-Pk","/var/lib/postgresql/data");if(df.getExitCode()!=0)throw new IllegalStateException("Owned Docker filesystem capacity unavailable");String[] lines=df.getStdout().trim().split("\\n");String[] cells=lines[lines.length-1].trim().split("\\s+");dockerUsable=Long.parseLong(cells[3])*1024L;record.put("dockerDataUsableBytes",dockerUsable);record.put("dockerDataTotalBytes",Long.parseLong(cells[1])*1024L);}
            Files.writeString(output.resolve("disk-guards.ndjson"),JSON.writeValueAsString(record)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            if(usable<2147483648L||dockerUsable<2147483648L){abortReason.compareAndSet(null,"DISK_RESERVE_BELOW_2_GIB");throw new IllegalStateException(abortReason.get());}
        }catch(RuntimeException e){abortReason.compareAndSet(null,"DISK_GUARD_UNAVAILABLE");throw e;}catch(Exception e){abortReason.compareAndSet(null,"DISK_GUARD_UNAVAILABLE");throw new IllegalStateException("Disk guard failed",e);}
    }
    static String commandOutput(List<String> command,int seconds)throws Exception{
        var process=new ProcessBuilder(command).redirectErrorStream(true).start();if(!process.waitFor(seconds,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("Environment observation timeout");}String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);if(process.exitValue()!=0)throw new IllegalStateException("Environment observation failed");return output.trim();
    }
    Map<String,Object> source()throws Exception{
        var p=new ProcessBuilder("git","rev-parse","HEAD").start();String commit=new String(p.getInputStream().readAllBytes()).trim();if(p.waitFor()!=0)throw new IllegalStateException("Cannot identify source commit");
        var dirty=new ProcessBuilder("git","status","--porcelain").start();String status=new String(dirty.getInputStream().readAllBytes());dirty.waitFor();
        var digests=new TreeMap<String,String>();var combined=java.security.MessageDigest.getInstance("SHA-256");
        for(String directory:List.of("src/main/java","src/main/resources","src/test/java/com/paymentsledger/pr20/performance")){try(var paths=Files.walk(Path.of(directory))){for(Path file:paths.filter(Files::isRegularFile).sorted().toList()){byte[] bytes=Files.readAllBytes(file);digests.put(file.toString(),java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));combined.update(file.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));combined.update((byte)0);combined.update(bytes);}}}
        return Map.of("commit",commit,"workingTreeDirty",!status.isBlank(),"sourceTreeSha256",java.util.HexFormat.of().formatHex(combined.digest()),"sourceFileSha256",digests,"invocation",System.getProperty("sun.java.command","").replace(PerformanceFixture.SECRET,"[REDACTED]"));
    }
    record Options(long history,int warmup,int measure,int writeRps,int queryRps,int drain,long seed,Path output){
        static Options parse(String[] args){
            Map<String,String> values=new LinkedHashMap<>();for(int n=0;n<args.length;n+=2){if(n+1>=args.length||!args[n].startsWith("--"))throw new IllegalArgumentException("Expected --option value pairs");if(values.put(args[n].substring(2),args[n+1])!=null)throw new IllegalArgumentException("Duplicate option");}
            for(String key:values.keySet())if(!Set.of("history","warmup-seconds","measure-seconds","write-rps","query-rps","drain-seconds","seed","output").contains(key))throw new IllegalArgumentException("Unknown option "+key);
            long seed=Long.parseLong(values.getOrDefault("seed","20261010"));
            var o=new Options(Long.parseLong(values.getOrDefault("history","1000000")),Integer.parseInt(values.getOrDefault("warmup-seconds","300")),Integer.parseInt(values.getOrDefault("measure-seconds","1800")),Integer.parseInt(values.getOrDefault("write-rps","100")),Integer.parseInt(values.getOrDefault("query-rps","200")),Integer.parseInt(values.getOrDefault("drain-seconds","120")),seed,Path.of(values.getOrDefault("output","target/pr20/performance/"+Instant.now().toString().replace(':','-')+"-"+UUID.randomUUID())));
            if(o.history<1||o.warmup<0||o.measure<1||o.writeRps<1||o.queryRps<1||o.drain<0)throw new IllegalArgumentException("Invalid experiment budget");
            Path base=Path.of("target/pr20/performance").toAbsolutePath().normalize(),out=o.output.toAbsolutePath().normalize();if(!out.startsWith(base)||out.equals(base))throw new IllegalArgumentException("Output must be a new run directory beneath target/pr20/performance");return o;
        }
        boolean full(){return PerformancePolicy.fullBudget(history,warmup,measure,writeRps,queryRps);}
        Map<String,Object> map(){return Map.of("history",history,"warmupSeconds",warmup,"measureSeconds",measure,"writeRps",writeRps,"queryRps",queryRps,"drainSeconds",drain,"seed",seed,"output",output.toString());}
    }
}
