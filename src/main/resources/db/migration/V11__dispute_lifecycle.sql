-- PR13: provider state, immutable evidence and actual financial history remain separate.
CREATE TABLE platform.dispute (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, environment_id uuid NOT NULL,
 provider_account_id uuid NOT NULL, livemode boolean NOT NULL, provider_dispute_id text NOT NULL,
 application_id uuid, payment_id uuid, provider_charge_id text, currency text NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 provider_status text, reason text, evidence_due_by timestamptz, last_observed_at timestamptz NOT NULL,
 local_handling text NOT NULL DEFAULT 'WAITING_EVIDENCE' CHECK(local_handling IN('NEEDS_REVIEW','WAITING_EVIDENCE','RESOLVED')),
 blocking boolean NOT NULL DEFAULT true, conflict boolean NOT NULL DEFAULT false,
 disputed_principal_minor bigint NOT NULL CHECK(disputed_principal_minor>0),
 outstanding_receivable_minor bigint NOT NULL DEFAULT 0 CHECK(outstanding_receivable_minor>=0),
 confirmed_loss_minor bigint NOT NULL DEFAULT 0 CHECK(confirmed_loss_minor>=0),
 debited_principal_minor bigint NOT NULL DEFAULT 0 CHECK(debited_principal_minor>=0),
 returned_principal_minor bigint NOT NULL DEFAULT 0 CHECK(returned_principal_minor>=0),
 dispute_fee_minor bigint NOT NULL DEFAULT 0,
 loss_generation bigint NOT NULL DEFAULT 0 CHECK(loss_generation>=0),
 last_query_attempt_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(outstanding_receivable_minor::numeric+confirmed_loss_minor+returned_principal_minor=debited_principal_minor),
 CHECK((payment_id IS NULL)=(application_id IS NULL)),
 CHECK(blocking OR (NOT conflict AND local_handling='RESOLVED' AND outstanding_receivable_minor=0 AND confirmed_loss_minor=0)),
 UNIQUE(organization_id,environment_id,provider_account_id,livemode,provider_dispute_id),
 UNIQUE(organization_id,environment_id,provider_account_id,livemode,id),
 UNIQUE(organization_id,environment_id,currency,id),
 FOREIGN KEY(organization_id,environment_id,provider_account_id) REFERENCES platform.provider_account(organization_id,environment_id,id),
 FOREIGN KEY(organization_id,environment_id,livemode) REFERENCES platform.environment_config(organization_id,id,livemode),
 FOREIGN KEY(organization_id,environment_id,application_id,provider_account_id,currency,payment_id)
 REFERENCES platform.payment(organization_id,environment_id,application_id,provider_account_id,currency,id)
);
CREATE INDEX dispute_payment ON platform.dispute(payment_id,id);
CREATE TABLE platform.dispute_observation (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), dispute_id uuid NOT NULL REFERENCES platform.dispute(id),
 fact jsonb NOT NULL CHECK(jsonb_typeof(fact)='object'), evidence jsonb NOT NULL CHECK(jsonb_typeof(evidence)='object'),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE platform.dispute_bt_link (
 dispute_id uuid NOT NULL REFERENCES platform.dispute(id), bt_id text NOT NULL CHECK(length(btrim(bt_id))>0),
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(dispute_id,bt_id)
);
CREATE TABLE platform.dispute_case (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), dispute_id uuid NOT NULL REFERENCES platform.dispute(id),
 reason text NOT NULL, evidence jsonb NOT NULL CHECK(jsonb_typeof(evidence)='object'), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(dispute_id,reason)
);
ALTER TABLE platform.balance_transaction ADD COLUMN dispute_id uuid,
 ADD FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,dispute_id)
 REFERENCES platform.dispute(organization_id,environment_id,provider_account_id,livemode,id);
CREATE TABLE platform.dispute_financial_fact (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, environment_id uuid NOT NULL, currency text NOT NULL,
 dispute_id uuid NOT NULL, balance_transaction_id uuid UNIQUE REFERENCES platform.balance_transaction(id),
 journal_id uuid NOT NULL UNIQUE, kind text NOT NULL CHECK(kind IN('DEBIT','RETURN','FEE','LOSS')),
 principal_minor bigint NOT NULL CHECK(principal_minor>=0), fee_minor bigint NOT NULL DEFAULT 0,
 receivable_delta bigint NOT NULL, loss_delta bigint NOT NULL, suspense_minor bigint NOT NULL DEFAULT 0 CHECK(suspense_minor>=0),
 loss_generation bigint, created_at timestamptz NOT NULL DEFAULT now(),
 CHECK((kind='LOSS')=(balance_transaction_id IS NULL)), CHECK((kind='LOSS')=(loss_generation IS NOT NULL)),
 FOREIGN KEY(organization_id,environment_id,currency,dispute_id) REFERENCES platform.dispute(organization_id,environment_id,currency,id),
 FOREIGN KEY(organization_id,environment_id,currency,journal_id) REFERENCES platform.journal(organization_id,environment_id,currency,id),
 UNIQUE(dispute_id,loss_generation)
);
CREATE FUNCTION platform.pr13_identity_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Immutable dispute identity' USING ERRCODE='23514'; END IF;
 IF (NEW.id,NEW.organization_id,NEW.environment_id,NEW.provider_account_id,NEW.livemode,NEW.provider_dispute_id,NEW.disputed_principal_minor)
 IS DISTINCT FROM (OLD.id,OLD.organization_id,OLD.environment_id,OLD.provider_account_id,OLD.livemode,OLD.provider_dispute_id,OLD.disputed_principal_minor)
 OR NEW.currency IS DISTINCT FROM OLD.currency AND NOT (OLD.payment_id IS NULL AND NEW.payment_id IS NOT NULL AND OLD.debited_principal_minor=0 AND OLD.dispute_fee_minor=0)
 OR OLD.payment_id IS NOT NULL AND (NEW.payment_id,NEW.application_id,NEW.provider_charge_id) IS DISTINCT FROM(OLD.payment_id,OLD.application_id,OLD.provider_charge_id)
 THEN RAISE EXCEPTION 'Immutable dispute identity' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER dispute_identity BEFORE UPDATE OR DELETE ON platform.dispute FOR EACH ROW EXECUTE FUNCTION platform.pr13_identity_guard();
CREATE TRIGGER immutable_dispute_observation BEFORE UPDATE OR DELETE ON platform.dispute_observation FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence();
CREATE TRIGGER immutable_dispute_link BEFORE UPDATE OR DELETE ON platform.dispute_bt_link FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence();
CREATE TRIGGER immutable_dispute_case BEFORE UPDATE OR DELETE ON platform.dispute_case FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence();
CREATE TRIGGER immutable_dispute_financial_fact BEFORE UPDATE OR DELETE ON platform.dispute_financial_fact FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence();
CREATE FUNCTION platform.pr13_history_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
DECLARE d platform.dispute%ROWTYPE; f platform.dispute_financial_fact%ROWTYPE; e platform.financial_event%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='dispute' THEN SELECT * INTO STRICT d FROM platform.dispute WHERE id=NEW.id;
 ELSE SELECT * INTO STRICT d FROM platform.dispute WHERE id=NEW.dispute_id;
  SELECT ev.* INTO STRICT e FROM platform.journal j JOIN platform.financial_event ev ON ev.id=j.financial_event_id WHERE j.id=NEW.journal_id AND j.sealed_at IS NOT NULL;
  IF NEW.kind='LOSS' THEN
   IF e.event_key<>'dispute.loss:'||d.id||':'||NEW.loss_generation OR e.source_domain<>'INTERNAL' OR NEW.receivable_delta<>-NEW.principal_minor OR NEW.loss_delta<>NEW.principal_minor OR NEW.fee_minor<>0 OR NEW.suspense_minor<>0
   THEN RAISE EXCEPTION 'Invalid dispute loss journal' USING ERRCODE='23514'; END IF;
  ELSE
   IF NOT EXISTS(SELECT 1 FROM platform.balance_transaction b WHERE b.id=NEW.balance_transaction_id AND b.dispute_id=d.id AND b.payment_id=d.payment_id AND b.journal_id=NEW.journal_id AND b.state='POSTED' AND e.event_key='bt:'||d.provider_account_id||':'||d.livemode||':'||b.bt_id)
   THEN RAISE EXCEPTION 'Invalid dispute BT journal' USING ERRCODE='23514'; END IF;
  END IF;
  IF (SELECT coalesce(sum(CASE WHEN je.side='DEBIT' THEN je.amount_minor::numeric ELSE -je.amount_minor::numeric END),0) FROM platform.journal_entry je JOIN platform.ledger_account a ON a.id=je.ledger_account_id WHERE je.journal_id=NEW.journal_id AND a.account_purpose='DISPUTE_RECEIVABLE')<>NEW.receivable_delta
  OR (SELECT coalesce(sum(CASE WHEN je.side='DEBIT' THEN je.amount_minor::numeric ELSE -je.amount_minor::numeric END),0) FROM platform.journal_entry je JOIN platform.ledger_account a ON a.id=je.ledger_account_id WHERE je.journal_id=NEW.journal_id AND a.account_purpose='DISPUTE_LOSS')<>NEW.loss_delta
  OR (SELECT coalesce(sum(CASE WHEN je.side='DEBIT' THEN je.amount_minor::numeric ELSE -je.amount_minor::numeric END),0) FROM platform.journal_entry je JOIN platform.ledger_account a ON a.id=je.ledger_account_id WHERE je.journal_id=NEW.journal_id AND a.account_purpose='DISPUTE_FEE_EXPENSE')<>NEW.fee_minor
  OR (SELECT coalesce(sum(CASE WHEN je.side='DEBIT' THEN je.amount_minor::numeric ELSE -je.amount_minor::numeric END),0) FROM platform.journal_entry je JOIN platform.ledger_account a ON a.id=je.ledger_account_id WHERE je.journal_id=NEW.journal_id AND a.account_purpose='SUSPENSE')<>-NEW.suspense_minor
  OR EXISTS(SELECT 1 FROM platform.journal_entry je JOIN platform.ledger_account a ON a.id=je.ledger_account_id WHERE je.journal_id=NEW.journal_id AND a.account_purpose NOT IN('PSP_FUNDS','DISPUTE_RECEIVABLE','DISPUTE_LOSS','DISPUTE_FEE_EXPENSE','SUSPENSE'))
  THEN RAISE EXCEPTION 'Dispute amounts must match sealed journal lines' USING ERRCODE='23514'; END IF;
  IF NEW.kind='LOSS' AND EXISTS(SELECT 1 FROM platform.journal_entry je JOIN platform.ledger_account a ON a.id=je.ledger_account_id WHERE je.journal_id=NEW.journal_id AND a.account_purpose='PSP_FUNDS')
  THEN RAISE EXCEPTION 'Dispute loss cannot move provider funds' USING ERRCODE='23514'; END IF;
  IF NEW.kind<>'LOSS' AND NOT EXISTS(SELECT 1 FROM platform.balance_transaction b WHERE b.id=NEW.balance_transaction_id AND b.fee_minor=NEW.fee_minor AND abs(b.amount_minor::numeric)=NEW.principal_minor AND
   (NEW.kind='DEBIT' AND b.amount_minor<0 AND NEW.receivable_delta=NEW.principal_minor AND NEW.loss_delta=0 AND NEW.suspense_minor=0
    OR NEW.kind='RETURN' AND b.amount_minor>0 AND NEW.receivable_delta<=0 AND NEW.loss_delta<=0 AND -NEW.receivable_delta::numeric-NEW.loss_delta+NEW.suspense_minor=NEW.principal_minor
    OR NEW.kind='FEE' AND b.amount_minor=0 AND NEW.receivable_delta=0 AND NEW.loss_delta=0 AND NEW.suspense_minor=0)
   AND b.net_minor=(SELECT coalesce(sum(CASE WHEN je.side='DEBIT' THEN je.amount_minor::numeric ELSE -je.amount_minor::numeric END),0) FROM platform.journal_entry je JOIN platform.ledger_account a ON a.id=je.ledger_account_id WHERE je.journal_id=NEW.journal_id AND a.account_purpose='PSP_FUNDS'))
  THEN RAISE EXCEPTION 'Dispute financial facts must match provider minor units' USING ERRCODE='23514'; END IF;
 END IF;
 IF d.outstanding_receivable_minor<>(SELECT coalesce(sum(receivable_delta),0) FROM platform.dispute_financial_fact WHERE dispute_id=d.id)
 OR d.confirmed_loss_minor<>(SELECT coalesce(sum(loss_delta),0) FROM platform.dispute_financial_fact WHERE dispute_id=d.id)
 OR d.debited_principal_minor<>(SELECT coalesce(sum(principal_minor),0) FROM platform.dispute_financial_fact WHERE dispute_id=d.id AND kind='DEBIT')
 OR d.returned_principal_minor<>(SELECT coalesce(sum(principal_minor-suspense_minor),0) FROM platform.dispute_financial_fact WHERE dispute_id=d.id AND kind='RETURN')
 OR d.dispute_fee_minor<>(SELECT coalesce(sum(fee_minor),0) FROM platform.dispute_financial_fact WHERE dispute_id=d.id)
 OR d.loss_generation<>(SELECT count(*) FROM platform.dispute_financial_fact WHERE dispute_id=d.id AND kind='LOSS')
 THEN RAISE EXCEPTION 'Dispute counters require immutable financial history' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER dispute_history AFTER INSERT OR UPDATE ON platform.dispute DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.pr13_history_guard();
CREATE CONSTRAINT TRIGGER dispute_fact_history AFTER INSERT ON platform.dispute_financial_fact DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.pr13_history_guard();
CREATE FUNCTION platform.ensure_dispute_accounts(payment_arg uuid) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,platform AS $$
BEGIN
 INSERT INTO platform.ledger_account(organization_id,environment_id,currency,account_purpose,source_domain,provider_account_id,balance_domain)
 SELECT p.organization_id,p.environment_id,p.currency,purpose,'PROVIDER',p.provider_account_id,'PAYMENTS' FROM platform.payment p
 CROSS JOIN(VALUES('PSP_FUNDS'),('DISPUTE_RECEIVABLE'),('DISPUTE_FEE_EXPENSE'),('SUSPENSE')) purposes(purpose) WHERE p.id=payment_arg ORDER BY purpose ON CONFLICT DO NOTHING;
 INSERT INTO platform.ledger_account(organization_id,environment_id,currency,account_purpose,source_domain)
 SELECT organization_id,environment_id,currency,'DISPUTE_LOSS','INTERNAL' FROM platform.payment WHERE id=payment_arg ON CONFLICT DO NOTHING;
END $$;
REVOKE ALL ON FUNCTION platform.ensure_dispute_accounts(uuid),platform.pr13_identity_guard(),platform.pr13_history_guard() FROM PUBLIC;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='payments_app') THEN
  GRANT SELECT,INSERT,UPDATE ON platform.dispute TO payments_app;
  GRANT SELECT,INSERT ON platform.dispute_observation,platform.dispute_bt_link,platform.dispute_case,platform.dispute_financial_fact TO payments_app;
  GRANT EXECUTE ON FUNCTION platform.ensure_dispute_accounts(uuid) TO payments_app;
 END IF;
END $$;

CREATE OR REPLACE FUNCTION platform.pr12_ceiling_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
DECLARE payment_uuid uuid; p platform.payment%ROWTYPE; c platform.refund_control%ROWTYPE; base numeric;
BEGIN
 IF TG_TABLE_NAME='payment' THEN payment_uuid:=NEW.id; ELSE payment_uuid:=NEW.payment_id; END IF; IF payment_uuid IS NULL THEN RETURN NULL; END IF;
 SELECT * INTO STRICT p FROM platform.payment WHERE id=payment_uuid;
 SELECT * INTO c FROM platform.refund_control WHERE payment_id=payment_uuid;
 base:=greatest(0,p.captured_amount_minor::numeric-coalesce(c.refunded_minor,0)-coalesce(c.reserved_minor,0));
 IF EXISTS(SELECT 1 FROM platform.dispute d WHERE d.payment_id=p.id AND (d.blocking OR d.conflict OR EXISTS(SELECT 1 FROM platform.balance_transaction b WHERE b.organization_id=d.organization_id AND b.environment_id=d.environment_id AND b.provider_account_id=d.provider_account_id AND b.livemode=d.livemode AND (b.dispute_id=d.id OR b.source_object_id=d.provider_dispute_id OR b.bt_id IN(SELECT bt_id FROM platform.dispute_bt_link WHERE dispute_id=d.id)) AND (b.state='QUARANTINED' OR EXISTS(SELECT 1 FROM platform.bt_evidence_conflict ec WHERE ec.balance_transaction_id=b.id))))) OR p.resolution_status<>'KNOWN' OR p.financial_status='CONFLICT' OR coalesce(c.control_state,'NORMAL')<>'NORMAL'
 OR coalesce((SELECT sum(amount_minor) FROM platform.balance_transaction WHERE payment_id=p.id AND state='POSTED' AND lower(business_type)='charge' AND amount_minor>0),0)<>p.captured_amount_minor
 OR EXISTS(SELECT 1 FROM platform.balance_transaction b WHERE (b.state='QUARANTINED' OR EXISTS(SELECT 1 FROM platform.bt_evidence_conflict x WHERE x.balance_transaction_id=b.id)) AND (b.payment_id=p.id OR b.refund_id IN(SELECT id FROM platform.refund WHERE payment_id=p.id)
  OR EXISTS(SELECT 1 FROM platform.payment_attempt a WHERE a.payment_id=p.id AND a.organization_id=b.organization_id AND a.environment_id=b.environment_id AND a.provider_account_id=b.provider_account_id AND a.provider_object_id=b.source_object_id)
  OR EXISTS(SELECT 1 FROM platform.refund r WHERE r.payment_id=p.id AND r.organization_id=b.organization_id AND r.environment_id=b.environment_id AND r.provider_account_id=b.provider_account_id AND r.provider_refund_id=b.source_object_id))) THEN base:=0; END IF;
 IF EXISTS(SELECT 1 FROM platform.share_allocation WHERE payment_id=p.id AND (effective_minor>floor(base*rate_bps/10000) OR cap_minor>floor(base*rate_bps/10000)))
 THEN RAISE EXCEPTION 'Share exceeds cumulative eligible ceiling' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER share_cap_dispute AFTER INSERT OR UPDATE ON platform.dispute DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.pr12_ceiling_guard();

-- Prelock the union of capture, refund, dispute and share accounts before the first journal.
-- All financial replay paths use the same order, including payments sharing these accounts.
CREATE FUNCTION platform.lock_dispute_accounts(payment_arg uuid) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,platform AS $$
BEGIN
 PERFORM a.id FROM platform.ledger_account a JOIN platform.payment p ON p.organization_id=a.organization_id AND p.environment_id=a.environment_id AND p.currency=a.currency
 WHERE p.id=payment_arg AND ((a.provider_account_id=p.provider_account_id AND a.source_domain='PROVIDER' AND a.balance_domain='PAYMENTS' AND a.account_purpose IN('PSP_FUNDS','PSP_FEE_EXPENSE','DISPUTE_RECEIVABLE','DISPUTE_FEE_EXPENSE','SUSPENSE'))
 OR (a.source_domain='INTERNAL' AND a.provider_account_id IS NULL AND a.account_purpose IN('SALES_REVENUE','SALES_RETURNS','DISPUTE_LOSS','PARTNER_SHARE_EXPENSE','PARTNER_PAYABLE')))
 ORDER BY a.id FOR UPDATE OF a;
END $$;
REVOKE ALL ON FUNCTION platform.lock_dispute_accounts(uuid) FROM PUBLIC;
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='payments_app') THEN GRANT EXECUTE ON FUNCTION platform.lock_dispute_accounts(uuid) TO payments_app; END IF; END $$;

CREATE FUNCTION platform.pr13_bt_link_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
BEGIN
 IF TG_OP='UPDATE' AND OLD.journal_id IS NOT NULL AND NEW.dispute_id IS DISTINCT FROM OLD.dispute_id
 THEN RAISE EXCEPTION 'Immutable posted dispute association' USING ERRCODE='23514'; END IF;
 IF NEW.dispute_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM platform.dispute d WHERE d.id=NEW.dispute_id AND d.payment_id=NEW.payment_id AND d.currency=NEW.currency AND d.provider_dispute_id=NEW.source_object_id)
 THEN RAISE EXCEPTION 'Dispute source linkage mismatch' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER dispute_bt_link_guard BEFORE INSERT OR UPDATE ON platform.balance_transaction FOR EACH ROW EXECUTE FUNCTION platform.pr13_bt_link_guard();
REVOKE ALL ON FUNCTION platform.pr13_bt_link_guard() FROM PUBLIC;
