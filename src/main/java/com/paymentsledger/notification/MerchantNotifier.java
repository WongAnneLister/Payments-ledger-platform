package com.paymentsledger.notification;

import java.time.Duration;

public final class MerchantNotifier {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(MerchantNotifier.class);
    private final DeliveryRepository repository;
    private final MerchantTransport transport;
    private final Duration lease,base,cap;
    private final int maxAttempts;
    public MerchantNotifier(DeliveryRepository repository,MerchantTransport transport) {
        this(repository,transport,Duration.ofSeconds(30),8,Duration.ofSeconds(5),Duration.ofHours(1));
    }
    public MerchantNotifier(DeliveryRepository repository,MerchantTransport transport,Duration lease,int maxAttempts,Duration base,Duration cap) {
        this.repository=repository;this.transport=transport;this.lease=lease;this.maxAttempts=maxAttempts;this.base=base;this.cap=cap;
    }
    public int drain(int limit) {
        ConsumerInboxService.outsideTransaction();int processed=0;
        for(int i=0;i<Math.min(100,limit);i++) {
            var claim=repository.claim(lease,maxAttempts);if(claim==null)break;
            if(!repository.recheck(claim))continue;
            MerchantTransport.Result result;
            try {result=transport.send(claim.target(),claim.event().id(),claim.event().businessKey(),claim.event().bytes());}
            catch(RuntimeException e){result=new MerchantTransport.Result(MerchantTransport.Outcome.RETRYABLE,0,"TRANSPORT_UNAVAILABLE");}
            if(repository.complete(claim,result,maxAttempts,base,cap))
                LOG.info("Merchant notification attempt completed task={} event={} outcome={} status={}",claim.id(),claim.event().id(),result.outcome(),result.status());
            processed++;
        }
        return processed;
    }
}
