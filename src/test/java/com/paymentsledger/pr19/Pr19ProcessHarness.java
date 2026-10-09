package com.paymentsledger.pr19;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** File barrier and actual child JVM lifecycle. Secrets are supplied only in ProcessBuilder's environment. */
public final class Pr19ProcessHarness {
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static final long WAIT_SECONDS=25;
    private Pr19ProcessHarness() {}

    public static Child start(String mainClass,Path runDirectory,String actor,Map<String,String> environment) throws IOException {
        if(!actor.matches("[a-zA-Z0-9_-]+"))throw new IllegalArgumentException("Invalid actor identity");
        Path control=runDirectory.toAbsolutePath().resolve(actor);Files.createDirectories(control);
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var builder=new ProcessBuilder(java,"-cp",classpath,mainClass);
        // Do not inherit credentials for unrelated real integrations into the test actor.
        builder.environment().keySet().removeIf(k->k.startsWith("STRIPE_")||k.startsWith("AWS_")||k.contains("LIVE_KEY"));
        builder.environment().putAll(environment);
        builder.environment().put("PR19_CONTROL_DIR",control.toString());
        builder.environment().put("PR19_ACTOR_ID",actor);
        builder.redirectErrorStream(true).redirectOutput(control.resolve("child.log").toFile());
        return new Child(actor,control,builder.start());
    }
    public static void mark(String marker,Map<String,?> values) {
        try {
            Path control=childDirectory();Files.createDirectories(control);
            var body=new LinkedHashMap<String,Object>(values);body.put("javaPid",ProcessHandle.current().pid());
            body.put("actor",System.getenv("PR19_ACTOR_ID"));body.put("marker",marker);body.put("atUtc",Instant.now().toString());
            Path pending=control.resolve(valid(marker)+".tmp");JSON.writeValue(pending.toFile(),body);
            Files.move(pending,control.resolve(marker+".json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        } catch(IOException ex){throw new IllegalStateException("Cannot persist process marker",ex);}
    }
    public static void checkpoint(String marker,Map<String,?> values) {
        mark(marker,values);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        Path release=childDirectory().resolve(valid(marker)+".release");
        while(!Files.exists(release)) {
            if(System.nanoTime()>=deadline)throw new IllegalStateException("Timed out at child checkpoint: "+marker);
            pause();
        }
    }
    private static Path childDirectory(){return Path.of(Objects.requireNonNull(System.getenv("PR19_CONTROL_DIR"),"Child control directory required"));}
    private static String valid(String marker){if(!marker.matches("[a-zA-Z0-9_-]+"))throw new IllegalArgumentException("Invalid checkpoint");return marker;}
    private static void pause(){try{Thread.sleep(10);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted process barrier",ex);}}

    public static final class Child implements AutoCloseable {
        private final String actor;private final Path control;private final Process process;private final Path retainedLog;
        private final Map<String,Object> evidence=new LinkedHashMap<>();
        private Child(String actor,Path control,Process process){this.actor=actor;this.control=control;this.process=process;this.retainedLog=Path.of(System.getProperty("pr19.evidenceDir","target/pr19"),"process-logs",actor+"-"+process.pid()+".log").toAbsolutePath();evidence.put("logFile",retainedLog.toString());evidence.put("actor",actor);evidence.put("javaPid",process.pid());evidence.put("parentPid",ProcessHandle.current().pid());evidence.put("startedAtUtc",Instant.now().toString());evidence.put("forceKilled",false);evidence.put("waitDeadlineSeconds",WAIT_SECONDS);}
        public JsonNode awaitMarker(String marker) throws IOException {
            Path file=control.resolve(valid(marker)+".json");long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while(!Files.exists(file)) {
                if(!process.isAlive())throw new IllegalStateException("Child "+actor+" exited before "+marker+"; see "+control.resolve("child.log"));
                if(System.nanoTime()>=deadline)throw new IllegalStateException("Timed out awaiting "+actor+"/"+marker);
                pause();
            }
            JsonNode body=JSON.readTree(file.toFile());evidence.put(marker,body);return body;
        }
        public void release(String marker) throws IOException {Files.writeString(control.resolve(valid(marker)+".release"),Instant.now().toString());evidence.put(marker+"ReleasedAtUtc",Instant.now().toString());}
        public JsonNode awaitResult() throws Exception {
            var result=awaitMarker("result");
            if(!process.waitFor(WAIT_SECONDS,TimeUnit.SECONDS))throw new IllegalStateException("Child did not exit after result: "+actor);
            evidence.put("exitCode",process.exitValue());evidence.put("exitedAtUtc",Instant.now().toString());
            if(process.exitValue()!=0||!"PASS".equals(result.path("status").asText()))throw new IllegalStateException("Child action failed: "+actor+"; see "+control.resolve("child.log"));
            return result;
        }
        public void killForcibly() throws Exception {
            if(!process.isAlive())throw new IllegalStateException("Child exited before forcible kill: "+actor);
            evidence.put("forceKilled",true);evidence.put("killRequestedAtUtc",Instant.now().toString());process.destroyForcibly();
            if(!process.waitFor(5,TimeUnit.SECONDS))throw new IllegalStateException("Child survived forcible termination: "+actor);
            evidence.put("exitCode",process.exitValue());evidence.put("exitedAtUtc",Instant.now().toString());
        }
        public Map<String,Object> evidence(){return new LinkedHashMap<>(evidence);}
        @Override public void close() throws Exception {
            if(process.isAlive()){process.destroyForcibly();if(!process.waitFor(5,TimeUnit.SECONDS))throw new IllegalStateException("Unreaped child: "+actor);}
            Files.createDirectories(retainedLog.getParent());Files.copy(control.resolve("child.log"),retainedLog,StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
