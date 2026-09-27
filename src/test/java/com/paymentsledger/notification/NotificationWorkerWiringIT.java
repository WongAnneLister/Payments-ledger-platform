package com.paymentsledger.notification;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
@SpringBootTest(properties={"platform.messaging.enabled=true","platform.messaging.topic=pr09-wiring"})
@ActiveProfiles("worker")
@Import(NotificationWorkerWiringIT.TopicConfiguration.class)
@DirtiesContext
class NotificationWorkerWiringIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16");
    @Container static final KafkaContainer KAFKA=new KafkaContainer("apache/kafka:3.9.0")
            .withEnv("KAFKA_LISTENERS","PLAINTEXT://0.0.0.0:9092,BROKER://0.0.0.0:9093,CONTROLLER://localhost:9094");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);
        p.add("spring.flyway.url",DB::getJdbcUrl);p.add("spring.flyway.user",DB::getUsername);p.add("spring.flyway.password",DB::getPassword);
        p.add("spring.kafka.bootstrap-servers",KAFKA::getBootstrapServers);
    }
    @TestConfiguration static class TopicConfiguration {
        @Bean NewTopic notificationTopic(){return TopicBuilder.name("pr09-wiring").partitions(1).replicas(1).build();}
    }
    @Autowired ApplicationContext context;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Test void optInWorkerStartsTheActualConfiguredListenerAndProductionTransport() {
        assertThat(context.getBean(MerchantTransport.class)).isInstanceOf(HttpMerchantTransport.class);
        assertThat(context.getBean(NotificationWorker.class)).isNotNull();
        assertThat(context.getBean(NotificationMetrics.class).value("outbox_backlog")).isZero();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(()->assertThat(listeners.getListenerContainers())
                .anySatisfy(container->assertThat(container.getAssignedPartitions()).isNotEmpty()));
    }
}
