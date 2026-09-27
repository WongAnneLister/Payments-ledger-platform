package com.paymentsledger.notification;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Actual Kafka 3.9.0 acknowledgments and outages, with PostgreSQL as durable authority. */
class NotificationKafkaIT extends NotificationFixture {
    @Container static final KafkaContainer KAFKA=new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.0"))
            // Kafka 3.9 validates the implicit controller advertisement; it must be routable.
            .withEnv("KAFKA_LISTENERS","PLAINTEXT://0.0.0.0:9092,BROKER://0.0.0.0:9093,CONTROLLER://localhost:9094");
    @Test void brokerDuplicateAfterSendBeforeMarkAndCommitBeforeAckCreatesOneTask() throws Exception {
        var event=success();String topic="notification-"+UUID.randomUUID();
        String group="crash-"+UUID.randomUUID();
        try(var producer=producer()) {
            var claim=outbox().claim(Duration.ofSeconds(30));
            producer.send(new ProducerRecord<>(topic,claim.event().businessKey(),claim.event().bytes())).get(15,TimeUnit.SECONDS);
            admin.update("UPDATE platform.outbox_event SET lease_until=now()-interval '1 second' WHERE id=?",claim.event().id());
            var replay=outbox().claim(Duration.ofSeconds(30));
            assertThat(replay.event().bytes()).isEqualTo(claim.event().bytes());
            producer.send(new ProducerRecord<>(topic,replay.event().businessKey(),replay.event().bytes())).get(15,TimeUnit.SECONDS);
            assertThat(outbox().published(claim)).isFalse();assertThat(outbox().published(replay)).isTrue();
            org.apache.kafka.clients.consumer.ConsumerRecord<String,byte[]> first;
            try(var beforeCrash=consumer(topic,group)) {
                first=pollOne(beforeCrash);inbox().accept(first.value());
                assertThat(beforeCrash.committed(Set.of(new org.apache.kafka.common.TopicPartition(topic,first.partition()))).values()).allMatch(Objects::isNull);
                // Close this real consumer without committing; a new process uses the same durable group.
            }
            try(var afterCrash=consumer(topic,group)) {
                var replayed=pollOne(afterCrash);
                assertThat(replayed.offset()).isEqualTo(first.offset());
                assertThat(replayed.value()).isEqualTo(first.value());
                var partition=new org.apache.kafka.common.TopicPartition(topic,replayed.partition());
                inbox().consume(replayed.value(),()->afterCrash.commitSync(Map.of(partition,new OffsetAndMetadata(replayed.offset()+1))));
                assertThat(afterCrash.committed(Set.of(partition)).get(partition).offset()).isEqualTo(replayed.offset()+1);
                // Both acknowledged producer sends have identical identity; the second record is also deduplicated.
                afterCrash.seek(partition,replayed.offset()+1);
                var duplicate=pollOne(afterCrash);
                inbox().consume(duplicate.value(),()->afterCrash.commitSync(Map.of(partition,new OffsetAndMetadata(duplicate.offset()+1))));
                assertThat(count("consumer_inbox")).isEqualTo(1);assertThat(count("delivery_task")).isEqualTo(1);
            }
        }
        assertThat(outbox().find(event.id())).isNotNull();
    }
    @Test void pausedBrokerLeavesDurableWorkAndUnpausePublishes() throws Exception {
        success();String topic="outage-"+UUID.randomUUID();
        try(var producer=producer()) {
            // Ensure the client has established a route before the deterministic outage.
            producer.partitionsFor(topic);
            var publisher=new OutboxPublisher(outbox(),event->producer.send(new ProducerRecord<>(topic,event.businessKey(),event.bytes())).get(4,TimeUnit.SECONDS),Duration.ofSeconds(30));
            KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
            try {assertThat(publisher.drain(1)).isZero();}
            finally {KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();}
            assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.outbox_event WHERE status='PUBLISHED'",Long.class)).isZero();
            admin.update("UPDATE platform.outbox_event SET available_at=now()-interval '1 minute'");
            assertThat(publisher.drain(1)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.outbox_event WHERE status='PUBLISHED'",Long.class)).isEqualTo(1);
        }
    }
    @Test void configuredSpringListenerDoesNotCommitDatabaseFailuresAndRecoversDurably() throws Exception {
        var event=success();String topic="spring-"+UUID.randomUUID(),group="spring-group-"+UUID.randomUUID();
        var properties=new org.springframework.boot.autoconfigure.kafka.KafkaProperties();
        properties.setBootstrapServers(List.of(KAFKA.getBootstrapServers()));
        var config=new NotificationKafkaConfiguration();
        var factory=config.notificationListenerFactory(config.notificationConsumerFactory(properties));
        var container=factory.createContainer(topic);container.getContainerProperties().setGroupId(group);
        var failed=new java.util.concurrent.CountDownLatch(1);var delivered=new java.util.concurrent.CountDownLatch(1);
        var seen=new java.util.concurrent.atomic.AtomicReference<org.apache.kafka.common.TopicPartition>();
        container.getContainerProperties().setMessageListener((org.springframework.kafka.listener.AcknowledgingMessageListener<String,byte[]>)(record,ack)->{
            seen.set(new org.apache.kafka.common.TopicPartition(record.topic(),record.partition()));
            try {inbox().consume(record.value(),()->{ack.acknowledge();delivered.countDown();});}
            catch(RuntimeException error){failed.countDown();throw error;}
        });
        admin.execute("CREATE OR REPLACE FUNCTION platform.kafka_delivery_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected database failure'; END $$");
        admin.execute("CREATE TRIGGER kafka_delivery_failure BEFORE INSERT ON platform.delivery_task FOR EACH ROW EXECUTE FUNCTION platform.kafka_delivery_failure()");
        try(var producer=producer();var observer=consumer(topic,group)) {
            producer.send(new ProducerRecord<>(topic,event.businessKey(),event.bytes())).get(15,TimeUnit.SECONDS);
            container.start();
            assertThat(failed.await(30,TimeUnit.SECONDS)).isTrue();
            assertThat(observer.committed(Set.of(seen.get())).get(seen.get())).isNull();
            assertThat(count("consumer_inbox")).isZero();
            admin.execute("DROP TRIGGER kafka_delivery_failure ON platform.delivery_task");
            assertThat(delivered.await(20,TimeUnit.SECONDS)).isTrue();
            assertThat(observer.committed(Set.of(seen.get())).get(seen.get()).offset()).isEqualTo(1);
            assertThat(count("delivery_task")).isEqualTo(1);
        } finally {
            container.stop();
            admin.execute("DROP TRIGGER IF EXISTS kafka_delivery_failure ON platform.delivery_task");
        }
    }
    KafkaProducer<String,byte[]> producer() {
        Map<String,Object> p=new HashMap<>();p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class);p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,ByteArraySerializer.class);
        p.put(ProducerConfig.ACKS_CONFIG,"all");p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,true);
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,1000);p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,3000);p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,5000);
        return new KafkaProducer<>(p);
    }
    org.apache.kafka.clients.consumer.ConsumerRecord<String,byte[]> pollOne(KafkaConsumer<String,byte[]> consumer) {
        long until=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        while(System.nanoTime()<until) {
            var records=consumer.poll(Duration.ofMillis(500));
            if(!records.isEmpty())return records.iterator().next();
        }
        throw new AssertionError("Kafka record not received within deadline");
    }
    KafkaConsumer<String,byte[]> consumer(String topic,String group) {
        Map<String,Object> p=new HashMap<>();p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,ByteArrayDeserializer.class);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");p.put(ConsumerConfig.GROUP_ID_CONFIG,group);p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,1);
        var c=new KafkaConsumer<String,byte[]>(p);c.subscribe(List.of(topic));return c;
    }
}
