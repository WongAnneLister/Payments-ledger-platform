package com.paymentsledger.dispute;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.error.*;
import com.paymentsledger.ledger.LedgerPostingService;
import com.paymentsledger.payment.RefundEligibilityPolicy;
import com.paymentsledger.revenueshare.*;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
@Configuration(proxyBeanMethods=false)
public class DisputeConfiguration {
 @Bean DisputeService disputeService(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper mapper,LedgerPostingService ledger,RevenueShareService shares){return new DisputeService(jdbc,manager,mapper,ledger,shares);}
 @Bean ShareEligibilityPolicy disputeShareEligibility(ObjectProvider<DisputeService> service){return payment->!service.getObject().blocks(payment);}
 @Bean RefundEligibilityPolicy disputeRefundEligibility(ObjectProvider<DisputeService> service){return (scope,payment,control)->{if(service.getObject().blocks((UUID)payment.get("id")))throw new ApiException(ErrorCode.REFUND_BLOCKED,"Payment is blocked by dispute evidence");};}
}
