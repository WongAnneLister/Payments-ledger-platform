package com.paymentsledger.notification;

import static org.assertj.core.api.Assertions.*;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.listener.ContainerProperties;

class NotificationConfigurationTest {
    @Test void workerOptInAndManualCommitCannotBeOverriddenByKafkaProperties() {
        var config=new NotificationKafkaConfiguration();var properties=new KafkaProperties();
        properties.getConsumer().setEnableAutoCommit(true);
        var factory=config.notificationConsumerFactory(properties);
        assertThat(factory.getConfigurationProperties()).containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);
        assertThat(config.notificationListenerFactory(factory).getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        var producer=config.notificationProducerFactory(properties);
        assertThat(producer.getConfigurationProperties()).containsEntry(ProducerConfig.ACKS_CONFIG,"all").containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,true);
        var gate=NotificationKafkaConfiguration.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(gate.havingValue()).isEqualTo("true");assertThat(gate.matchIfMissing()).isFalse();
        assertThat(NotificationKafkaConfiguration.class.getAnnotation(Profile.class).value()).containsExactly("worker");
    }
}
