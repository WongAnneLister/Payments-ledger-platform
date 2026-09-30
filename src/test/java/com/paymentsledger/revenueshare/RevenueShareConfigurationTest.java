package com.paymentsledger.revenueshare;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.ledger.LedgerPostingService;
import com.paymentsledger.provider.ProviderScope;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
class RevenueShareConfigurationTest {
 private ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(RevenueShareConfiguration.class)
  .withBean(JdbcTemplate.class,()->mock(JdbcTemplate.class)).withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class))
  .withBean(ObjectMapper.class,ObjectMapper::new).withBean(LedgerPostingService.class,()->mock(LedgerPostingService.class));}
 @Test void applicationGraphEnablesHooksAndAllowsExplicitRolloutFlag(){
  context().run(c->assertThat(c.getBean(RevenueShareService.class).enabled()).isTrue());
  context().withPropertyValues("platform.revenue-share.enabled=false").run(c->assertThat(c.getBean(RevenueShareService.class).enabled()).isFalse());
 }
 @Test void workerRunsABoundedScopedBatch(){
  var service=mock(RevenueShareService.class);var scope=new ProviderScope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),false);
  new RevenueShareWorker(service,scope).tick();verify(service).catchUp(scope.organizationId(),scope.environmentId(),scope.providerAccountId(),25);
 }
 @Test void enabledLockRequiresAnExistingTransaction(){
  context().run(c->assertThatThrownBy(()->c.getBean(RevenueShareService.class).lock(UUID.randomUUID())).isInstanceOf(IllegalStateException.class));
 }
 @Test void compatibilityNoOpDoesNotNeedATransaction(){
  var service=RevenueShareService.disabled();var id=UUID.randomUUID();service.reconcile(service.lock(id),"legacy");service.reconcile(id,"legacy");
  assertThat(service.catchUp(id,id,id,25)).isZero();
 }
}
