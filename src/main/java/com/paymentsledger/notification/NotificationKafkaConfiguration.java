package com.paymentsledger.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods=false)
@Profile("worker")
@ConditionalOnProperty(name="platform.messaging.enabled",havingValue="true")
@EnableKafka
@EnableScheduling
public class NotificationKafkaConfiguration {
    @Bean public ProducerFactory<String,byte[]> notificationProducerFactory(KafkaProperties properties) {
        var values=properties.buildProducerProperties();
        values.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class);
        values.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,ByteArraySerializer.class);
        values.put(ProducerConfig.ACKS_CONFIG,"all");values.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,true);
        values.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,5000);values.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,5000);
        values.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,15000);
        return new DefaultKafkaProducerFactory<>(values);
    }
    @Bean public KafkaTemplate<String,byte[]> notificationKafkaTemplate(ProducerFactory<String,byte[]> notificationProducerFactory) {
        return new KafkaTemplate<>(notificationProducerFactory);
    }
    @Bean public ConsumerFactory<String,byte[]> notificationConsumerFactory(KafkaProperties properties) {
        var values=properties.buildConsumerProperties();
        values.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
        values.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,ByteArrayDeserializer.class);
        values.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);
        values.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
        values.put(ConsumerConfig.GROUP_ID_CONFIG,ConsumerInboxService.CONSUMER_NAME);
        return new DefaultKafkaConsumerFactory<>(values);
    }
    @Bean public ConcurrentKafkaListenerContainerFactory<String,byte[]> notificationListenerFactory(ConsumerFactory<String,byte[]> notificationConsumerFactory) {
        var factory=new ConcurrentKafkaListenerContainerFactory<String,byte[]>();
        factory.setConsumerFactory(notificationConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // Database failures must never be "recovered" only in memory or committed by the container.
        var errors=new DefaultErrorHandler((record,error)->{throw new IllegalStateException("Notification persistence unavailable",error);},
                new FixedBackOff(1000L,FixedBackOff.UNLIMITED_ATTEMPTS));
        errors.setAckAfterHandle(false);errors.setCommitRecovered(false);
        factory.setCommonErrorHandler(errors);return factory;
    }
    @Bean public OutboxRepository outboxRepository(JdbcTemplate jdbc,PlatformTransactionManager tx,Clock clock){return new OutboxRepository(jdbc,tx,clock);}
    @Bean public OutboxWriter outboxWriter(JdbcTemplate jdbc){return new OutboxWriter(jdbc);}
    @Bean public ConsumerInboxService consumerInboxService(JdbcTemplate jdbc,PlatformTransactionManager tx,ObjectMapper json,Clock clock){return new ConsumerInboxService(jdbc,tx,json,clock);}
    @Bean public FulfillmentEligibility fulfillmentEligibility(JdbcTemplate jdbc){return new FulfillmentEligibility.Database(jdbc);}
    @Bean public DeliveryRepository deliveryRepository(JdbcTemplate jdbc,PlatformTransactionManager tx,Clock clock,FulfillmentEligibility policy){return new DeliveryRepository(jdbc,tx,clock,policy);}
    @Bean public MerchantNotifier merchantNotifier(DeliveryRepository repository,MerchantTransport transport,Environment env) {
        return new MerchantNotifier(repository,transport,Duration.ofSeconds(30),
                env.getProperty("platform.notifications.max-attempts",Integer.class,8),Duration.ofSeconds(5),Duration.ofHours(1));
    }
    @Bean public OutboxPublisher outboxPublisher(OutboxRepository repository,KafkaTemplate<String,byte[]> notificationKafkaTemplate,Environment env) {
        String topic=env.getProperty("platform.messaging.topic","payment-notifications-v1");
        return new OutboxPublisher(repository,event->notificationKafkaTemplate.send(topic,event.businessKey(),event.bytes()).get(20,TimeUnit.SECONDS),Duration.ofSeconds(30));
    }
    @Bean public NotificationWorker notificationWorker(OutboxPublisher publisher,MerchantNotifier notifier,ConsumerInboxService inbox){return new NotificationWorker(publisher,notifier,inbox);}
    @Bean public NotificationMetrics notificationMetrics(JdbcTemplate jdbc,Clock clock,MeterRegistry meters) {
        var metrics=new NotificationMetrics(jdbc,clock);metrics.bind(meters);return metrics;
    }
}
