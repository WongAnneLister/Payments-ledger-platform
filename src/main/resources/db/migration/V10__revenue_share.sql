-- PR12: immutable partner history; scoped aggregate internal expense/payable accounts.
ALTER TABLE platform.share_policy_fixture ADD COLUMN allocations jsonb,
 ADD CHECK(allocations IS NULL OR jsonb_typeof(allocations)='array');
CREATE TABLE platform.share_allocation (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, environment_id uuid NOT NULL,
 application_id uuid NOT NULL, provider_account_id uuid NOT NULL, currency text NOT NULL, payment_id uuid NOT NULL,
 partner_ref text NOT NULL CHECK(length(btrim(partner_ref)) BETWEEN 1 AND 128), policy_version text NOT NULL,
 rate_bps integer NOT NULL CHECK(rate_bps BETWEEN 0 AND 10000),
 effective_minor bigint NOT NULL DEFAULT 0 CHECK(effective_minor>=0), cap_minor bigint NOT NULL DEFAULT 0 CHECK(cap_minor>=0),
 generation bigint NOT NULL DEFAULT 0 CHECK(generation>=0), updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(effective_minor<=cap_minor), UNIQUE(payment_id,partner_ref),
 UNIQUE(organization_id,environment_id,currency,id),
 FOREIGN KEY(organization_id,environment_id,application_id,provider_account_id,currency,payment_id)
 REFERENCES platform.payment(organization_id,environment_id,application_id,provider_account_id,currency,id)
);
CREATE TABLE platform.share_adjustment (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, environment_id uuid NOT NULL,
 currency text NOT NULL, allocation_id uuid NOT NULL, generation bigint NOT NULL CHECK(generation>0),
 delta_minor bigint NOT NULL CHECK(delta_minor<>0 AND delta_minor<>-9223372036854775808),
 journal_id uuid NOT NULL UNIQUE, cause text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(allocation_id,generation),
 FOREIGN KEY(organization_id,environment_id,currency,allocation_id) REFERENCES platform.share_allocation(organization_id,environment_id,currency,id),
 FOREIGN KEY(organization_id,environment_id,currency,journal_id) REFERENCES platform.journal(organization_id,environment_id,currency,id)
);
CREATE TABLE platform.share_catchup_cursor (
 payment_id uuid PRIMARY KEY REFERENCES platform.payment(id), attempted_at timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION platform.pr12_allocation_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
DECLARE snapshot jsonb; partner jsonb;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Immutable allocation identity' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' THEN
  IF (NEW.id,NEW.organization_id,NEW.environment_id,NEW.application_id,NEW.provider_account_id,NEW.currency,NEW.payment_id,NEW.partner_ref,NEW.policy_version,NEW.rate_bps)
  IS DISTINCT FROM (OLD.id,OLD.organization_id,OLD.environment_id,OLD.application_id,OLD.provider_account_id,OLD.currency,OLD.payment_id,OLD.partner_ref,OLD.policy_version,OLD.rate_bps)
  THEN RAISE EXCEPTION 'Immutable allocation identity' USING ERRCODE='23514'; END IF;
 ELSE
  SELECT o.share_policy_snapshot INTO STRICT snapshot FROM platform.payment p JOIN platform.business_order o ON o.id=p.business_order_id WHERE p.id=NEW.payment_id;
  IF snapshot->'allocations' IS NOT NULL AND snapshot->'allocations'<>'null'::jsonb THEN
   SELECT value INTO partner FROM jsonb_array_elements(snapshot->'allocations') WHERE value->>'partner_ref'=NEW.partner_ref;
  ELSE partner:=snapshot; END IF;
  IF partner IS NULL OR partner->>'partner_ref' IS DISTINCT FROM NEW.partner_ref OR (partner->>'share_basis_points')::numeric IS DISTINCT FROM NEW.rate_bps::numeric OR snapshot->>'policy_version' IS DISTINCT FROM NEW.policy_version
  THEN RAISE EXCEPTION 'Allocation policy mismatch' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER share_allocation_identity BEFORE INSERT OR UPDATE OR DELETE ON platform.share_allocation FOR EACH ROW EXECUTE FUNCTION platform.pr12_allocation_guard();
CREATE TRIGGER immutable_share_adjustment BEFORE UPDATE OR DELETE ON platform.share_adjustment FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence();
CREATE FUNCTION platform.pr12_history_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
DECLARE a platform.share_allocation%ROWTYPE; j platform.journal%ROWTYPE; e platform.financial_event%ROWTYPE; expected_key text; total numeric; latest bigint;
BEGIN
 IF TG_TABLE_NAME='share_allocation' THEN SELECT * INTO STRICT a FROM platform.share_allocation WHERE id=NEW.id;
 ELSE SELECT * INTO STRICT a FROM platform.share_allocation WHERE id=NEW.allocation_id;
  SELECT * INTO STRICT j FROM platform.journal WHERE id=NEW.journal_id;
  SELECT * INTO STRICT e FROM platform.financial_event WHERE id=j.financial_event_id;
  expected_key:=CASE WHEN NEW.delta_minor>0 THEN 'share.accrue:' ELSE 'share.reverse:' END || a.payment_id || ':' || a.partner_ref || ':' || a.policy_version || ':' || NEW.generation;
  IF j.sealed_at IS NULL OR e.event_key<>expected_key OR e.source_domain<>'INTERNAL' OR e.provider_account_id IS NOT NULL OR e.source_ref<>a.id::text
   OR e.event_type IS DISTINCT FROM (CASE WHEN NEW.delta_minor>0 THEN 'share.accrue' ELSE 'share.reverse' END)
   OR e.facts->>'allocation_id' IS DISTINCT FROM a.id::text OR e.facts->>'delta_minor' IS DISTINCT FROM NEW.delta_minor::text
   OR e.facts->>'payment_id' IS DISTINCT FROM a.payment_id::text OR e.facts->>'partner_ref' IS DISTINCT FROM a.partner_ref
   OR e.facts->>'policy_version' IS DISTINCT FROM a.policy_version OR e.facts->>'generation' IS DISTINCT FROM NEW.generation::text OR e.facts->>'cause' IS DISTINCT FROM NEW.cause
   OR (SELECT count(*) FROM platform.journal_entry WHERE journal_id=j.id)<>2
   OR NOT EXISTS(SELECT 1 FROM platform.journal_entry l JOIN platform.ledger_account acc ON acc.id=l.ledger_account_id WHERE l.journal_id=j.id AND acc.account_purpose='PARTNER_SHARE_EXPENSE' AND l.amount_minor=abs(NEW.delta_minor) AND l.side=CASE WHEN NEW.delta_minor>0 THEN 'DEBIT' ELSE 'CREDIT' END)
   OR NOT EXISTS(SELECT 1 FROM platform.journal_entry l JOIN platform.ledger_account acc ON acc.id=l.ledger_account_id WHERE l.journal_id=j.id AND acc.account_purpose='PARTNER_PAYABLE' AND l.amount_minor=abs(NEW.delta_minor) AND l.side=CASE WHEN NEW.delta_minor>0 THEN 'CREDIT' ELSE 'DEBIT' END)
  THEN RAISE EXCEPTION 'Share history journal mismatch' USING ERRCODE='23514'; END IF;
 END IF;
 SELECT coalesce(sum(delta_minor),0),coalesce(max(generation),0) INTO total,latest FROM platform.share_adjustment WHERE allocation_id=a.id;
 IF total<>a.effective_minor OR latest<>a.generation OR (SELECT count(*) FROM platform.share_adjustment WHERE allocation_id=a.id)<>a.generation
 THEN RAISE EXCEPTION 'Allocation must equal immutable journal history' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER share_allocation_history AFTER INSERT OR UPDATE ON platform.share_allocation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.pr12_history_guard();
CREATE CONSTRAINT TRIGGER share_adjustment_history AFTER INSERT ON platform.share_adjustment DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.pr12_history_guard();
-- Deferred cap checks also reject direct control/allocation writes that omit atomic share adjustment.
CREATE FUNCTION platform.pr12_ceiling_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
DECLARE payment_uuid uuid; p platform.payment%ROWTYPE; c platform.refund_control%ROWTYPE; base numeric;
BEGIN
 IF TG_TABLE_NAME='payment' THEN payment_uuid:=NEW.id; ELSE payment_uuid:=NEW.payment_id; END IF;
 SELECT * INTO STRICT p FROM platform.payment WHERE id=payment_uuid;
 SELECT * INTO c FROM platform.refund_control WHERE payment_id=payment_uuid;
 base:=greatest(0,p.captured_amount_minor::numeric-coalesce(c.refunded_minor,0)-coalesce(c.reserved_minor,0));
 IF p.resolution_status<>'KNOWN' OR p.financial_status='CONFLICT' OR coalesce(c.control_state,'NORMAL')<>'NORMAL'
 OR coalesce((SELECT sum(amount_minor) FROM platform.balance_transaction WHERE payment_id=p.id AND state='POSTED' AND lower(business_type)='charge' AND amount_minor>0),0)<>p.captured_amount_minor
 OR EXISTS(SELECT 1 FROM platform.balance_transaction b WHERE (b.state='QUARANTINED' OR EXISTS(SELECT 1 FROM platform.bt_evidence_conflict x WHERE x.balance_transaction_id=b.id)) AND (b.payment_id=p.id OR b.refund_id IN(SELECT id FROM platform.refund WHERE payment_id=p.id)
  OR EXISTS(SELECT 1 FROM platform.payment_attempt a WHERE a.payment_id=p.id AND a.organization_id=b.organization_id AND a.environment_id=b.environment_id AND a.provider_account_id=b.provider_account_id AND a.provider_object_id=b.source_object_id)
  OR EXISTS(SELECT 1 FROM platform.refund r WHERE r.payment_id=p.id AND r.organization_id=b.organization_id AND r.environment_id=b.environment_id AND r.provider_account_id=b.provider_account_id AND r.provider_refund_id=b.source_object_id))) THEN base:=0; END IF;
 IF EXISTS(SELECT 1 FROM platform.share_allocation WHERE payment_id=p.id AND (effective_minor>floor(base*rate_bps/10000) OR cap_minor>floor(base*rate_bps/10000)))
 THEN RAISE EXCEPTION 'Share exceeds cumulative eligible ceiling' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER share_cap_allocation AFTER INSERT OR UPDATE ON platform.share_allocation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.pr12_ceiling_guard();
CREATE CONSTRAINT TRIGGER share_cap_payment AFTER UPDATE ON platform.payment DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.pr12_ceiling_guard();
CREATE CONSTRAINT TRIGGER share_cap_control AFTER INSERT OR UPDATE ON platform.refund_control DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.pr12_ceiling_guard();
REVOKE ALL ON FUNCTION platform.pr12_ceiling_guard() FROM PUBLIC;
-- Only provisions the two frozen internal purposes for an existing scoped payment.
CREATE FUNCTION platform.ensure_share_accounts(payment_args uuid[]) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,platform AS $$
BEGIN
 INSERT INTO platform.ledger_account(organization_id,environment_id,currency,account_purpose,source_domain)
 SELECT DISTINCT p.organization_id,p.environment_id,p.currency,purpose,'INTERNAL' FROM platform.payment p
 CROSS JOIN (VALUES('PARTNER_SHARE_EXPENSE'),('PARTNER_PAYABLE')) purposes(purpose) WHERE p.id=ANY(payment_args)
 ORDER BY p.organization_id,p.environment_id,p.currency,purpose ON CONFLICT DO NOTHING;
END $$;
CREATE FUNCTION platform.ensure_share_accounts(payment_arg uuid) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,platform AS $$
BEGIN PERFORM platform.ensure_share_accounts(ARRAY[payment_arg]); END $$;
REVOKE ALL ON FUNCTION platform.ensure_share_accounts(uuid[]) FROM PUBLIC;
-- Read-only runtime configuration cannot SELECT FOR UPDATE accounts; constrain locking to this batch's share pair.
CREATE FUNCTION platform.lock_share_accounts(payment_args uuid[]) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,platform AS $$
BEGIN
 PERFORM a.id FROM platform.ledger_account a WHERE a.source_domain='INTERNAL' AND a.provider_account_id IS NULL
 AND a.account_purpose IN('PARTNER_SHARE_EXPENSE','PARTNER_PAYABLE')
 AND EXISTS(SELECT 1 FROM platform.payment p WHERE p.id=ANY(payment_args) AND p.organization_id=a.organization_id AND p.environment_id=a.environment_id AND p.currency=a.currency)
 ORDER BY a.id FOR UPDATE;
END $$;
REVOKE ALL ON FUNCTION platform.lock_share_accounts(uuid[]) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform.ensure_share_accounts(uuid),platform.pr12_allocation_guard(),platform.pr12_history_guard() FROM PUBLIC;
-- Business state is finalized before outbox rank90; still part of the same committing transaction.
DROP TRIGGER payment_notification ON platform.payment;
CREATE CONSTRAINT TRIGGER payment_notification AFTER INSERT OR UPDATE ON platform.payment DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.pr09_payment_notification();
DROP TRIGGER refund_notification ON platform.refund;
CREATE CONSTRAINT TRIGGER refund_notification AFTER INSERT OR UPDATE ON platform.refund DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.pr11_refund_notification();
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='payments_app') THEN
  REVOKE ALL ON platform.share_allocation,platform.share_adjustment,platform.share_catchup_cursor FROM payments_app;
  GRANT SELECT,INSERT,UPDATE ON platform.share_allocation,platform.share_catchup_cursor TO payments_app;
  GRANT SELECT,INSERT ON platform.share_adjustment TO payments_app;
  GRANT EXECUTE ON FUNCTION platform.ensure_share_accounts(uuid),platform.ensure_share_accounts(uuid[]),platform.lock_share_accounts(uuid[]) TO payments_app;
  REVOKE ALL ON FUNCTION platform.pr12_allocation_guard(),platform.pr12_history_guard(),platform.pr12_ceiling_guard() FROM payments_app;
 END IF;
END $$;
