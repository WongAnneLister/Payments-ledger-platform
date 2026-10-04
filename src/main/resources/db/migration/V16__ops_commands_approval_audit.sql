-- PR16: immutable operational commands and audit; approvals append through existing post_ledger only.
CREATE TABLE platform.audit_log (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),organization_id uuid,environment_id uuid,
 actor_id uuid,role text NOT NULL,action text NOT NULL,target text NOT NULL,request_id text NOT NULL,
 CHECK((organization_id IS NULL AND environment_id IS NULL AND actor_id IS NULL AND role='ANONYMOUS') OR (organization_id IS NOT NULL AND environment_id IS NOT NULL AND actor_id IS NOT NULL)),
 reason_summary jsonb NOT NULL DEFAULT '{}',evidence_summary jsonb NOT NULL DEFAULT '{}',
 before_summary jsonb NOT NULL DEFAULT '{}',after_summary jsonb NOT NULL DEFAULT '{}',result text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(organization_id,environment_id) REFERENCES platform.environment_config(organization_id,id),
 FOREIGN KEY(organization_id,actor_id) REFERENCES platform.ops_identity(organization_id,id)
);
CREATE TABLE platform.ops_command (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),organization_id uuid NOT NULL,environment_id uuid NOT NULL,
 actor_id uuid NOT NULL,action text NOT NULL,target text NOT NULL,idempotency_key text NOT NULL,
 request_fingerprint text NOT NULL CHECK(request_fingerprint ~ '^[a-f0-9]{64}$'),result jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(organization_id,environment_id,action,target,idempotency_key),
 FOREIGN KEY(organization_id,environment_id) REFERENCES platform.environment_config(organization_id,id),
 FOREIGN KEY(organization_id,actor_id) REFERENCES platform.ops_identity(organization_id,id)
);
CREATE TABLE platform.ops_adjustment (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),organization_id uuid NOT NULL,environment_id uuid NOT NULL,
 provider_account_id uuid,currency text NOT NULL,target_type text NOT NULL CHECK(target_type IN('PAYMENT','REFUND','DISPUTE','PAYOUT','BALANCE','EXCEPTION')),target_id uuid NOT NULL,
 created_by uuid NOT NULL,approved_by uuid,status text NOT NULL DEFAULT 'PENDING_APPROVAL' CHECK(status IN('PENDING_APPROVAL','APPROVED','REJECTED')),
 entries jsonb NOT NULL CHECK(jsonb_typeof(entries)='array' AND jsonb_array_length(entries)>=2),
 evidence_refs jsonb NOT NULL CHECK(jsonb_typeof(evidence_refs)='array' AND jsonb_array_length(evidence_refs)>0),
 reason_digest text NOT NULL,reason_length integer NOT NULL CHECK(reason_length BETWEEN 1 AND 1000),posted_journal_id uuid,created_at timestamptz NOT NULL DEFAULT now(),approved_at timestamptz,
 CHECK(created_by <> approved_by),
 CHECK((status='PENDING_APPROVAL' AND approved_by IS NULL AND approved_at IS NULL AND posted_journal_id IS NULL)
 OR(status='APPROVED' AND approved_by IS NOT NULL AND approved_at IS NOT NULL AND posted_journal_id IS NOT NULL)
 OR(status='REJECTED' AND approved_by IS NOT NULL AND approved_at IS NOT NULL AND posted_journal_id IS NULL)),
 FOREIGN KEY(organization_id,created_by) REFERENCES platform.ops_identity(organization_id,id),
 FOREIGN KEY(organization_id,approved_by) REFERENCES platform.ops_identity(organization_id,id),
 FOREIGN KEY(organization_id,environment_id,currency) REFERENCES platform.org_currency_enabled(organization_id,environment_id,currency),
 FOREIGN KEY(organization_id,environment_id,provider_account_id) REFERENCES platform.provider_account(organization_id,environment_id,id),
 FOREIGN KEY(organization_id,environment_id,currency,posted_journal_id) REFERENCES platform.journal(organization_id,environment_id,currency,id)
);
ALTER TABLE platform.reconciliation_exception ADD UNIQUE(organization_id,environment_id,id);
ALTER TABLE platform.bank_statement ADD UNIQUE(organization_id,environment_id,id);
ALTER TABLE platform.journal ADD UNIQUE(organization_id,environment_id,id);
CREATE TABLE platform.ops_exception_action (
 action_order bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),exception_id uuid NOT NULL REFERENCES platform.reconciliation_exception(id),
 organization_id uuid NOT NULL,environment_id uuid NOT NULL,actor_id uuid NOT NULL,
 status text NOT NULL CHECK(status IN('INVESTIGATING','WAITING_EVIDENCE','RESOLVED','REOPENED')),
 evidence_refs jsonb NOT NULL DEFAULT '[]',resolution_journal_id uuid,reason_digest text NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(organization_id,actor_id) REFERENCES platform.ops_identity(organization_id,id),
 FOREIGN KEY(organization_id,environment_id) REFERENCES platform.environment_config(organization_id,id),
 FOREIGN KEY(organization_id,environment_id,exception_id) REFERENCES platform.reconciliation_exception(organization_id,environment_id,id),
 FOREIGN KEY(organization_id,environment_id,resolution_journal_id) REFERENCES platform.journal(organization_id,environment_id,id)
);
CREATE TABLE platform.ops_reconciliation_request (
 run_id uuid PRIMARY KEY REFERENCES platform.reconciliation_run(id),run_type text NOT NULL CHECK(run_type IN('TRANSACTION_LEVEL','BALANCE_LEVEL','PAYOUT_BANK_LEVEL','NIGHTLY_ALL')),
 lookback_days integer NOT NULL CHECK(lookback_days BETWEEN 0 AND 31),business_date date NOT NULL,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE platform.ops_bank_import (
 organization_id uuid NOT NULL,environment_id uuid NOT NULL,provider_account_id uuid NOT NULL,content_digest text NOT NULL CHECK(content_digest ~ '^[a-f0-9]{64}$'),
 statement_id uuid NOT NULL REFERENCES platform.bank_statement(id),fixture_id text NOT NULL,schema_version text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(organization_id,environment_id,provider_account_id,content_digest),
 UNIQUE(organization_id,environment_id,provider_account_id,fixture_id),
 FOREIGN KEY(organization_id,environment_id,provider_account_id) REFERENCES platform.provider_account(organization_id,environment_id,id),
 FOREIGN KEY(organization_id,environment_id,statement_id) REFERENCES platform.bank_statement(organization_id,environment_id,id)
);
-- A polymorphic target is selected only through this fixed table whitelist and must match
-- every financial scope dimension. Trigger execution uses existing read grants only.
CREATE FUNCTION platform.pr16_adjustment_target() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
DECLARE target_table text; target_org uuid; target_env uuid; target_currency text; target_provider uuid;
BEGIN
 target_table:=CASE NEW.target_type WHEN 'PAYMENT' THEN 'payment' WHEN 'REFUND' THEN 'refund'
 WHEN 'DISPUTE' THEN 'dispute' WHEN 'PAYOUT' THEN 'payout' WHEN 'BALANCE' THEN 'ledger_account'
 WHEN 'EXCEPTION' THEN 'reconciliation_exception' ELSE NULL END;
 IF target_table IS NULL THEN RAISE EXCEPTION 'Unsupported adjustment target' USING ERRCODE='23514'; END IF;
 EXECUTE format('SELECT organization_id,environment_id,currency,provider_account_id FROM platform.%I WHERE id=$1',target_table)
 INTO target_org,target_env,target_currency,target_provider USING NEW.target_id;
 IF target_org IS NULL OR (target_org,target_env,target_currency,target_provider)
 IS DISTINCT FROM (NEW.organization_id,NEW.environment_id,NEW.currency,NEW.provider_account_id)
 THEN RAISE EXCEPTION 'Adjustment target scope mismatch' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER ops_adjustment_target BEFORE INSERT ON platform.ops_adjustment FOR EACH ROW EXECUTE FUNCTION platform.pr16_adjustment_target();
REVOKE ALL ON FUNCTION platform.pr16_adjustment_target() FROM PUBLIC;
CREATE INDEX ops_audit_scope ON platform.audit_log(organization_id,environment_id,created_at,id);
CREATE FUNCTION platform.pr16_adjustment_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.status<>'PENDING_APPROVAL' OR NEW.status='PENDING_APPROVAL'
 OR (to_jsonb(NEW)-ARRAY['status','approved_by','approved_at','posted_journal_id']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','approved_by','approved_at','posted_journal_id'])
 THEN RAISE EXCEPTION 'Immutable adjustment intent or final approval' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER ops_adjustment_guard BEFORE UPDATE OR DELETE ON platform.ops_adjustment FOR EACH ROW EXECUTE FUNCTION platform.pr16_adjustment_guard();
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['audit_log','ops_command','ops_exception_action','ops_reconciliation_request','ops_bank_import'] LOOP
 EXECUTE format('CREATE TRIGGER immutable_ops_evidence BEFORE UPDATE OR DELETE ON platform.%I FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence()',t);
 END LOOP;
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='payments_app') THEN
 FOREACH t IN ARRAY ARRAY['audit_log','ops_command','ops_adjustment','ops_exception_action','ops_reconciliation_request','ops_bank_import'] LOOP
 EXECUTE format('REVOKE ALL ON platform.%I FROM PUBLIC,payments_app',t);
 EXECUTE format('GRANT SELECT,INSERT ON platform.%I TO payments_app',t);
 END LOOP;
 -- The database generates chronological action evidence after the exception advisory lock.
 -- Runtime cannot supply action_order or backdate created_at.
 REVOKE INSERT ON platform.ops_exception_action FROM payments_app;
 GRANT INSERT(id,exception_id,organization_id,environment_id,actor_id,status,evidence_refs,resolution_journal_id,reason_digest) ON platform.ops_exception_action TO payments_app;
 GRANT USAGE ON SEQUENCE platform.ops_exception_action_action_order_seq TO payments_app;
 GRANT UPDATE(status,approved_by,approved_at,posted_journal_id) ON platform.ops_adjustment TO payments_app;
 END IF;
END $$;
REVOKE ALL ON FUNCTION platform.pr16_adjustment_guard() FROM PUBLIC;
