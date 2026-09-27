package com.paymentsledger.notification;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;

/** Instantiated exclusively by the opt-in worker configuration. */
public final class NotificationWorker {
    private final OutboxPublisher publisher;
    private final MerchantNotifier notifier;
    private final ConsumerInboxService inbox;
    public NotificationWorker(OutboxPublisher publisher,MerchantNotifier notifier,ConsumerInboxService inbox) {
        this.publisher=publisher;this.notifier=notifier;this.inbox=inbox;
    }
    @Scheduled(fixedDelayString="${platform.notifications.poll-ms:1000}",initialDelayString="${platform.notifications.poll-ms:1000}")
    public void publish(){publisher.drain(25);}
    @Scheduled(fixedDelayString="${platform.notifications.poll-ms:1000}",initialDelayString="${platform.notifications.poll-ms:1000}")
    public void deliver(){notifier.drain(25);}
    @KafkaListener(topics="${platform.messaging.topic:payment-notifications-v1}",groupId=ConsumerInboxService.CONSUMER_NAME,
            containerFactory="notificationListenerFactory")
    public void consume(ConsumerRecord<String,byte[]> record,Acknowledgment acknowledgment) {
        inbox.consume(record.value(),acknowledgment::acknowledge);
    }
}
