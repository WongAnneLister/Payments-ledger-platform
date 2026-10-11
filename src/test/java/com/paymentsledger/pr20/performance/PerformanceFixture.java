package com.paymentsledger.pr20.performance;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

final class PerformanceFixture {
    static final UUID ORG=new UUID(20,1),ENV=new UUID(20,2),APP=new UUID(20,3),PROVIDER=new UUID(20,4),CREDENTIAL=new UUID(20,5),POLICY=new UUID(20,6),PRICE=new UUID(20,7);
    static final String SECRET="pr20_"+"p".repeat(38);
    private PerformanceFixture(){}
    static void seed(JdbcTemplate db,long history,String merchant,java.util.function.LongConsumer diskGuard){
        diskGuard.accept(0);
        db.update("INSERT INTO platform.currency_config(code,minor_unit_exponent) VALUES('USD',2)");
        db.update("INSERT INTO platform.organization(id,code) VALUES(?,'pr20-performance')",ORG);
        db.update("INSERT INTO platform.environment_config(id,organization_id,code,livemode) VALUES(?,?,'test',false)",ENV,ORG);
        db.update("INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,provider_account_ref,livemode) VALUES(?,?,?,'FAKE','pr20-sql-external',false)",PROVIDER,ORG,ENV);
        db.update("INSERT INTO platform.application(id,organization_id,environment_id,code) VALUES(?,?,?,'pr20-load')",APP,ORG,ENV);
        db.update("INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency) VALUES(?,?,'USD')",ORG,ENV);
        db.update("INSERT INTO platform.share_policy_fixture(id,organization_id,environment_id,product_ref,policy_version,partner_ref,share_basis_points,valid_from) VALUES(?,?,?,'pr20-product',1,'pr20-partner',1000,now()-interval '400 days')",POLICY,ORG,ENV);
        db.update("INSERT INTO platform.price_fixture(id,organization_id,environment_id,product_ref,price_version,currency,amount_minor,share_policy_id,valid_from) VALUES(?,?,?,'pr20-product',1,'USD',100,?,now()-interval '400 days')",PRICE,ORG,ENV,POLICY);
        db.update("INSERT INTO platform.application_credential(id,key_id,organization_id,environment_id,application_id,provider_account_id,secret_hash,not_before,expires_at,granted_scopes) VALUES(?,'pr20',?,?,?,?,?,now()-interval '1 day',now()+interval '2 days',ARRAY['payments:write','payments:read'])",CREDENTIAL,ORG,ENV,APP,PROVIDER,new BCryptPasswordEncoder(10).encode(SECRET));
        db.update("INSERT INTO platform.credential_currency(credential_id,organization_id,environment_id,currency) VALUES(?,?,?,'USD')",CREDENTIAL,ORG,ENV);
        db.update("INSERT INTO platform.ingestion_policy(organization_id,environment_id,provider_account_id,livemode,opening_mode,business_zone) VALUES(?,?,?,false,'ZERO','UTC')",ORG,ENV,PROVIDER);
        db.update("INSERT INTO platform.merchant_notification_target(organization_id,environment_id,application_id,url,key_ref,version) VALUES(?,?,?,?,'pr20',1)",ORG,ENV,APP,merchant);
        // Synthetic historical UNKNOWN operations are permanently escalated. Their external outcome is intentionally unknown.
        // All associated immutable rows and constraint/notification triggers remain active.
        String scoped="'"+ORG+"'::uuid,'"+ENV+"'::uuid,'"+APP+"'::uuid,'"+PROVIDER+"'::uuid,'USD'";
        for(long first=1;first<=history;first+=10000){
            long last=Math.min(history,first+9999);diskGuard.accept(first-1);
            db.execute("CREATE TEMP TABLE pr20_seed ON COMMIT PRESERVE ROWS AS SELECT i,md5('pr20-order-'||i)::uuid oid,md5('pr20-payment-'||i)::uuid pid,md5('pr20-operation-'||i)::uuid opid,100*((i%10)+1)::bigint amount,date_trunc('second',now())-((i%365)+1)*interval '1 day' born FROM generate_series("+first+","+last+") i");
            db.execute("INSERT INTO platform.business_order(id,organization_id,environment_id,application_id,provider_account_id,currency,order_key,request_fingerprint,product_ref,price_version,quantity,unit_amount_minor,amount_minor,price_snapshot,share_policy_snapshot,created_at) SELECT oid,"+scoped+",'pr20-history-'||i,encode(sha256(convert_to('pr20-history-'||i,'UTF8')),'hex'),'pr20-product',1,(i%10)+1,100,amount,(SELECT to_jsonb(p) FROM platform.price_fixture p WHERE id='"+PRICE+"'),(SELECT to_jsonb(s) FROM platform.share_policy_fixture s WHERE id='"+POLICY+"'),born FROM pr20_seed");
            db.execute("INSERT INTO platform.payment(id,organization_id,environment_id,application_id,provider_account_id,currency,business_order_id,amount_minor,payment_status,resolution_status,unresolved_since,expires_at,created_at) SELECT pid,"+scoped+",oid,amount,'CREATED','UNKNOWN',born,born+interval '1 hour',born FROM pr20_seed");
            String metadata="jsonb_build_object('payment_id',pid,'business_order_id',oid,'organization_id','"+ORG+"','environment_id','"+ENV+"','application_id','"+APP+"','provider_account_id','"+PROVIDER+"')";
            db.execute("INSERT INTO platform.provider_operation(id,organization_id,environment_id,application_id,provider_account_id,currency,operation_target,payment_id,operation_type,operation_key,request_fingerprint,command,expires_at,status,resolution,first_send_at,retry_until,attempt_count,escalation_reason,escalated_at,created_at) SELECT opid,"+scoped+",'PAYMENT',pid,'CREATE_CHECKOUT','pay_create_'||opid,encode(sha256(convert_to('pr20-history-'||i,'UTF8')),'hex'),jsonb_build_object('scope',jsonb_build_object('organizationId','"+ORG+"','environmentId','"+ENV+"','providerAccountId','"+PROVIDER+"','livemode',false),'operationIdempotencyKey','pay_create_'||opid,'amountMinor',amount,'currency','USD','successUrl','https://merchant.test/ok','cancelUrl','https://merchant.test/cancel','sessionMetadata',"+metadata+",'paymentIntentMetadata',"+metadata+",'expiresAt',to_char(date_trunc('second',born+interval '1 hour') AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')),born+interval '1 hour','ESCALATED','UNKNOWN',born,born+interval '1 day',1,'PR20_SYNTHETIC_HISTORICAL_UNKNOWN',born+interval '1 day',born FROM pr20_seed");
            db.execute("INSERT INTO platform.recovery_task(organization_id,environment_id,application_id,provider_account_id,currency,operation_id,payment_id,status,available_at,reason,escalated_at,created_at) SELECT "+scoped+",opid,pid,'ESCALATED',born+interval '1 day','PR20_SYNTHETIC_HISTORICAL_UNKNOWN',born+interval '1 day',born FROM pr20_seed");
            db.execute("INSERT INTO platform.api_idempotency_record(organization_id,environment_id,application_id,provider_account_id,currency,request_type,idempotency_key,request_fingerprint,payment_id,operation_id,created_at) SELECT "+scoped+",'CREATE_PAYMENT','pr20-history-'||i,encode(sha256(convert_to('pr20-history-'||i,'UTF8')),'hex'),pid,opid,born FROM pr20_seed");
            db.execute("DROP TABLE pr20_seed");
        }
        db.execute("ANALYZE");
    }
}
