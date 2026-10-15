package com.paymentsledger.revenueshare;

import com.paymentsledger.provider.ProviderScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Catch-up complements transactional hooks; it is never their correctness mechanism. */
public final class RevenueShareWorker {
    private static final Logger LOG = LoggerFactory.getLogger(RevenueShareWorker.class);
    private final RevenueShareService service;
    private final ProviderScope scope;

    public RevenueShareWorker(RevenueShareService service, ProviderScope scope) {
        this.service = service;
        this.scope = scope;
    }

    @Scheduled(
            fixedDelayString = "${platform.revenue-share.poll-ms:300000}",
            initialDelayString = "${platform.revenue-share.poll-ms:300000}")
    public void tick() {
        try {
            service.catchUp(scope.organizationId(), scope.environmentId(), scope.providerAccountId(), 25);
        } catch (RuntimeException e) {
            LOG.warn("share_catchup reason=RETRY_REQUIRED providerAccountId={}", scope.providerAccountId());
        }
    }
}
