package com.paymentsledger.notification;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.pr19.Pr19ProcessHarness;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
public final class Pr19NotificationChild {
 public static void main(String[] ignored)throws Exception {
  var env=System.getenv();var ds=new DriverManagerDataSource(env.get("PR19_JDBC_URL"),env.get("PR19_JDBC_USER"),env.get("PR19_JDBC_PASSWORD"));
  var jdbc=new JdbcTemplate(ds);var tx=new DataSourceTransactionManager(ds);var clock=Clock.fixed(Instant.parse(env.get("PR19_CLOCK")),ZoneOffset.UTC);
  var actor=Map.of("processPid",ProcessHandle.current().pid(),"backendPid",jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
  if("OUTBOX".equals(env.get("PR19_MODE"))){
   var claim=new OutboxRepository(jdbc,tx,clock).claim(Duration.ofSeconds(30));if(claim==null)throw new AssertionError("No durable outbox claim");
   try(var producer=Pr19KafkaClients.producer(env.get("PR19_BOOTSTRAP"))){
    var sent=producer.send(new ProducerRecord<>(env.get("PR19_TOPIC"),claim.event().businessKey(),claim.event().bytes())).get(10,TimeUnit.SECONDS);
    var marker=new LinkedHashMap<String,Object>(actor);marker.put("eventId",claim.event().id().toString());marker.put("leaseToken",claim.token().toString());marker.put("offset",sent.offset());marker.put("partition",sent.partition());
    Pr19ProcessHarness.checkpoint("outbox-sent",marker);
   }
  }else{
   try(var consumer=Pr19KafkaClients.consumer(env.get("PR19_BOOTSTRAP"),env.get("PR19_TOPIC"),env.get("PR19_GROUP"))){
    var r=Pr19KafkaClients.poll(consumer);var acceptance=new ConsumerInboxService(jdbc,tx,new ObjectMapper(),clock).accept(r.value());
    var marker=new LinkedHashMap<String,Object>(actor);marker.put("acceptance",acceptance.toString());marker.put("offset",r.offset());marker.put("partition",r.partition());
    Pr19ProcessHarness.checkpoint("consumer-committed",marker);
    Pr19KafkaClients.ack(consumer,r);
   }
  }
  Pr19ProcessHarness.mark("result",Map.of("status","PASS"));
 }
}
