package com.paymentsledger.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;

/** Persistent PREPARED queue only; no UNKNOWN resend/recovery policy is introduced by PR06. */
@Configuration(proxyBeanMethods=false)
@Profile("worker")
@EnableScheduling
@ConditionalOnProperty(name="platform.payments.worker.enabled",havingValue="true",matchIfMissing=true)
public class PaymentWorker {
    private final PaymentExecutor executor;
    public PaymentWorker(PaymentExecutor executor){this.executor=executor;}
    @Scheduled(fixedDelayString="${platform.payments.worker.poll-ms:5000}",initialDelayString="${platform.payments.worker.poll-ms:5000}")
    public void drain(){executor.drainPrepared(25);}
}
