package com.paymentsledger.payment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
@Configuration(proxyBeanMethods=false)
@Profile("worker")
@EnableScheduling
@ConditionalOnProperty(name="platform.payments.worker.enabled",havingValue="true",matchIfMissing=true)
public class PaymentWorker {
 private final OperationRecoveryCoordinator recovery;
 public PaymentWorker(OperationRecoveryCoordinator recovery){this.recovery=recovery;}
 @Scheduled(fixedDelayString="${platform.recovery.poll-ms:5000}",initialDelayString="${platform.recovery.poll-ms:5000}")
 public void drain(){recovery.recoverDue(25);}
 @Scheduled(fixedDelayString="${platform.recovery.l2-poll-ms:300000}",initialDelayString="${platform.recovery.l2-poll-ms:300000}")
 public void scan(){recovery.scanL2(100);}
}
