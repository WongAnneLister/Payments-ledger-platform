package com.paymentsledger.dispute;

import com.paymentsledger.financialingestion.DisputeSourceResolver;
import com.paymentsledger.provider.ProviderScope;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded known-source refresh also discovers late wins; failed items rotate durably. */
@Component
@Profile("worker")
@ConditionalOnProperty(name = "platform.financial-ingestion.enabled", havingValue = "true")
public final class DisputeRecoveryWorker {
    private final JdbcTemplate jdbc;
    private final DisputeSourceResolver source;
    private final ProviderScope scope;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DisputeRecoveryWorker.class);

    public DisputeRecoveryWorker(
            JdbcTemplate jdbc,
            DisputeSourceResolver source,
            @Value("${platform.financial-ingestion.organization-id:}") String organization,
            @Value("${platform.financial-ingestion.environment-id:}") String environment,
            @Value("${platform.financial-ingestion.provider-account-id:}") String provider) {
        this.jdbc = jdbc;
        this.source = source;
        this.scope = new ProviderScope(
                UUID.fromString(organization), UUID.fromString(environment), UUID.fromString(provider), false);
    }

    @Scheduled(
            scheduler = "financialIngestionScheduler",
            fixedDelayString = "${platform.dispute.poll-ms:300000}",
            initialDelayString = "${platform.dispute.poll-ms:300000}")
    public void tick() {
        var rows = jdbc.queryForList(
                "SELECT id,provider_dispute_id FROM platform.dispute WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND (blocking OR payment_id IS NULL) ORDER BY last_query_attempt_at NULLS FIRST,id LIMIT 25",
                scope.organizationId(),
                scope.environmentId(),
                scope.providerAccountId());
        for (var row : rows)
            try {
                jdbc.update(
                        "UPDATE platform.dispute SET last_query_attempt_at=clock_timestamp() WHERE id=?",
                        row.get("id"));
                source.resolve(scope, row.get("provider_dispute_id").toString());
            } catch (RuntimeException failure) {
                LOG.warn("dispute_recovery reason=RETRY_REQUIRED disputeId={}", row.get("id"));
            }
    }
}
