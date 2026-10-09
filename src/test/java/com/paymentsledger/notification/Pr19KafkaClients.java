package com.paymentsledger.notification;
import java.time.Duration;
import java.util.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
final class Pr19KafkaClients {
 static KafkaProducer<String,byte[]> producer(String bootstrap){
  Map<String,Object> p=new HashMap<>();p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap);p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class);p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,ByteArraySerializer.class);p.put(ProducerConfig.ACKS_CONFIG,"all");p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,true);p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,1000);p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,3000);p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,5000);return new KafkaProducer<>(p);
 }
 static KafkaConsumer<String,byte[]> consumer(String bootstrap,String topic,String group){
  Map<String,Object> p=new HashMap<>();p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap);p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,ByteArrayDeserializer.class);p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");p.put(ConsumerConfig.GROUP_ID_CONFIG,group);p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,1);p.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,6000);p.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG,1000);var c=new KafkaConsumer<String,byte[]>(p);c.subscribe(List.of(topic));return c;
 }
 static ConsumerRecord<String,byte[]> poll(KafkaConsumer<String,byte[]> c){
  long until=System.nanoTime()+Duration.ofSeconds(25).toNanos();while(System.nanoTime()<until){var records=c.poll(Duration.ofMillis(250));if(!records.isEmpty())return records.iterator().next();}throw new AssertionError("Kafka record unavailable within 25s");
 }
 static void ack(KafkaConsumer<String,byte[]> c,ConsumerRecord<String,byte[]> r){c.commitSync(Map.of(new org.apache.kafka.common.TopicPartition(r.topic(),r.partition()),new OffsetAndMetadata(r.offset()+1)),Duration.ofSeconds(5));}
}
