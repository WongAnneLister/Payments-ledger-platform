package com.paymentsledger.reconciliation;

import com.paymentsledger.provider.ProviderScope;
import java.math.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads versioned expectations only; no method changes actual fee facts or journals. */
public final class FeeModelService {
    public enum FeeDirection { FOLLOW_PRINCIPAL, EXPENSE, REBATE }
    public record Comparison(String reason,String modelVersion,Long expectedMinor,long actualMinor,BigInteger residual,UUID modelId) {
        public boolean modeled() {return expectedMinor!=null;}
        public boolean matches() {return modeled()&&residual.signum()==0;}
    }
    private final JdbcTemplate jdbc;
    public FeeModelService(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public Comparison compare(ProviderScope scope,String currency,String domain,String businessType,Instant at,long amount,long actual) {
        var models=jdbc.queryForList("""
            SELECT * FROM platform.fee_model WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND livemode=?
            AND currency=? AND balance_domain=? AND business_type=? AND effective_from<=? AND effective_until>?
            """,scope.organizationId(),scope.environmentId(),scope.providerAccountId(),scope.livemode(),currency,domain,businessType,Timestamp.from(at),Timestamp.from(at));
        if(models.size()!=1)return new Comparison(models.isEmpty()?"UNMODELED_FEE":"AMBIGUOUS_FEE_MODEL",null,null,actual,BigInteger.valueOf(actual),null);
        var model=models.getFirst();
        long principalSigned=FeeArithmetic.expected(amount,((Number)model.get("basis_points")).intValue(),((Number)model.get("flat_minor")).longValue(),model.get("rounding").toString());
        FeeDirection direction=FeeDirection.valueOf(model.get("fee_direction").toString());
        BigInteger magnitude=BigInteger.valueOf(principalSigned).abs();
        long expected=switch(direction) {
            case FOLLOW_PRINCIPAL -> principalSigned;
            case EXPENSE -> magnitude.longValueExact();
            case REBATE -> magnitude.negate().longValueExact();
        };
        BigInteger residual=BigInteger.valueOf(actual).subtract(BigInteger.valueOf(expected));
        return new Comparison(residual.signum()==0?"MODELED_FEE_MATCH":"FEE_MODEL_MISMATCH",model.get("model_version").toString(),expected,actual,residual,(UUID)model.get("id"));
    }
}
