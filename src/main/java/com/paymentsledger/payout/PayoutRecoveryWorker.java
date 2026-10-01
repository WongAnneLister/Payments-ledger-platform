package com.paymentsledger.payout;
import com.paymentsledger.financialingestion.PayoutSourceResolver;
import com.paymentsledger.provider.ProviderScope;
import java.time.*;import java.sql.Timestamp;import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
/** Bounded durable list, membership and known-payout refresh. Failed reads rotate instead of starving later work. */
@Component @Profile("worker") @ConditionalOnProperty(name="platform.financial-ingestion.enabled",havingValue="true")
public final class PayoutRecoveryWorker {
 private final JdbcTemplate jdbc;private final PayoutIngestionService reads;private final PayoutSourceResolver source;private final ProviderScope scope;private final Clock clock;
 private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(PayoutRecoveryWorker.class);
 public PayoutRecoveryWorker(JdbcTemplate jdbc,PayoutIngestionService reads,PayoutSourceResolver source,Clock clock,
  @Value("${platform.financial-ingestion.organization-id:}")String organization,@Value("${platform.financial-ingestion.environment-id:}")String environment,@Value("${platform.financial-ingestion.provider-account-id:}")String provider){this.jdbc=jdbc;this.reads=reads;this.source=source;this.clock=clock;this.scope=new ProviderScope(UUID.fromString(organization),UUID.fromString(environment),UUID.fromString(provider),false);}
 @Scheduled(scheduler="financialIngestionScheduler",fixedDelayString="${platform.payout.poll-ms:300000}",initialDelayString="${platform.payout.poll-ms:300000}")
 public void tick(){
  guarded(()->{
   Instant until=LocalDate.now(clock.withZone(ZoneOffset.UTC)).atStartOfDay(ZoneOffset.UTC).toInstant(),from=until.minus(Duration.ofDays(7));
   Long exists=jdbc.queryForObject("SELECT count(*) FROM platform.payout_read_run WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND kind='PAYOUTS' AND created_gte=? AND created_lt=?",Long.class,scope.organizationId(),scope.environmentId(),scope.providerAccountId(),Timestamp.from(from),Timestamp.from(until));
   if(exists==0)reads.createWindow(scope,from,until);
  });
  for(UUID id:jdbc.queryForList("SELECT id FROM platform.payout_read_run WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND NOT livemode AND state='INCOMPLETE' ORDER BY updated_at,id LIMIT 5",UUID.class,scope.organizationId(),scope.environmentId(),scope.providerAccountId()))guarded(()->reads.importPage(id));
  for(var p:jdbc.queryForList("SELECT * FROM platform.payout WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND NOT livemode ORDER BY last_query_attempt_at NULLS FIRST,id LIMIT 25",scope.organizationId(),scope.environmentId(),scope.providerAccountId()))guarded(()->{
   jdbc.update("UPDATE platform.payout SET last_query_attempt_at=clock_timestamp() WHERE id=?",p.get("id"));source.resolve(scope,p.get("provider_payout_id").toString());
   if(Boolean.TRUE.equals(p.get("automatic"))){
    UUID latest=jdbc.queryForObject("SELECT id FROM platform.payout_observation WHERE payout_id=? ORDER BY created_at DESC,id DESC LIMIT 1",UUID.class,p.get("id"));
    if(jdbc.queryForObject("SELECT count(*) FROM platform.payout_read_run WHERE payout_id=? AND source_observation_id=?",Long.class,p.get("id"),latest)==0)reads.createMembershipWindow(scope,(UUID)p.get("id"),Instant.EPOCH,clock.instant());
   }
  });
 }
 private void guarded(Runnable action){try{action.run();}catch(RuntimeException failure){LOG.warn("payout_recovery reason=RETRY_REQUIRED providerAccountId={}",scope.providerAccountId());}}
}
