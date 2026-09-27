package com.paymentsledger.webhook;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
@Configuration(proxyBeanMethods=false)
@Profile("worker")
@EnableScheduling
@ConditionalOnProperty(name="platform.webhooks.worker.enabled",havingValue="true",matchIfMissing=true)
public class WebhookWorker {
    private final WebhookProcessor processor;
    public WebhookWorker(WebhookProcessor processor){this.processor=processor;}
    @Scheduled(fixedDelayString="${platform.webhooks.worker.poll-ms:5000}",initialDelayString="${platform.webhooks.worker.poll-ms:5000}")
    public void drain(){processor.processDue(25);}
}
