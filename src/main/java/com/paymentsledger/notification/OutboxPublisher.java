package com.paymentsledger.notification;

import java.time.Duration;

/** At-least-once publication: a crash after broker acknowledgment repeats the same event bytes. */
public final class OutboxPublisher {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(OutboxPublisher.class);
    @FunctionalInterface public interface Sender { void send(NotificationEvent event) throws Exception; }
    private final OutboxRepository repository;
    private final Sender sender;
    private final Duration lease;
    public OutboxPublisher(OutboxRepository repository,Sender sender,Duration lease) {
        this.repository=repository;this.sender=sender;this.lease=lease;
    }
    public int drain(int limit) {
        ConsumerInboxService.outsideTransaction();int published=0;
        for(int i=0;i<Math.min(100,limit);i++) {
            var claim=repository.claim(lease);if(claim==null)break;
            try {sender.send(claim.event());}
            catch(Exception e) {
                repository.retry(claim,Duration.ofSeconds(Math.min(3600,5L*(1L<<Math.min(20,claim.attempt()-1)))));
                LOG.warn("Notification publication deferred event={} reason=BROKER_UNAVAILABLE",claim.event().id());
                if(e instanceof InterruptedException)Thread.currentThread().interrupt();
                break;
            }
            if(repository.published(claim)){published++;LOG.info("Notification published event={}",claim.event().id());}
        }
        return published;
    }
}
