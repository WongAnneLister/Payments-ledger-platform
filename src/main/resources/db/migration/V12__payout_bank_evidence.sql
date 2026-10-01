-- PR14: actual PSP movements and simulated bank evidence are separate immutable histories.
CREATE TABLE platform.payout (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),organization_id uuid NOT NULL,environment_id uuid NOT NULL,
 provider_account_id uuid NOT NULL,livemode boolean NOT NULL CHECK(NOT livemode),provider_payout_id text NOT NULL CHECK(length(btrim(provider_payout_id))>0),
 currency text NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),amount_minor bigint NOT NULL CHECK(amount_minor>0),automatic boolean NOT NULL,
 arrival_date timestamptz,provider_status text,failure_code text,last_observed_at timestamptz NOT NULL,last_query_attempt_at timestamptz,
 conflict boolean NOT NULL DEFAULT false,bank_match_state text NOT NULL DEFAULT 'UNMATCHED' CHECK(bank_match_state IN('UNMATCHED','PARTIAL','MATCHED','CONFLICT')),
 outgoing_minor bigint NOT NULL DEFAULT 0 CHECK(outgoing_minor>=0),transit_minor bigint NOT NULL DEFAULT 0 CHECK(transit_minor>=0),
 bank_confirmed_minor bigint NOT NULL DEFAULT 0 CHECK(bank_confirmed_minor>=0),returned_minor bigint NOT NULL DEFAULT 0 CHECK(returned_minor>=0),
 suspense_minor bigint NOT NULL DEFAULT 0 CHECK(suspense_minor>=0),psp_fee_minor bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(outgoing_minor::numeric=transit_minor::numeric+bank_confirmed_minor+returned_minor),CHECK(outgoing_minor<=amount_minor),
 UNIQUE(organization_id,environment_id,provider_account_id,livemode,provider_payout_id),
 UNIQUE(organization_id,environment_id,provider_account_id,livemode,id),UNIQUE(organization_id,environment_id,currency,id),
 FOREIGN KEY(organization_id,environment_id,provider_account_id) REFERENCES platform.provider_account(organization_id,environment_id,id),
 FOREIGN KEY(organization_id,environment_id,livemode) REFERENCES platform.environment_config(organization_id,id,livemode),
 FOREIGN KEY(organization_id,environment_id,currency) REFERENCES platform.org_currency_enabled(organization_id,environment_id,currency)
);
CREATE TABLE platform.payout_observation (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),payout_id uuid NOT NULL REFERENCES platform.payout(id),fact jsonb NOT NULL CHECK(jsonb_typeof(fact)='object'),
 evidence jsonb NOT NULL CHECK(jsonb_typeof(evidence)='object'),created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE platform.payout_bt_link (
 payout_id uuid NOT NULL REFERENCES platform.payout(id),bt_id text NOT NULL CHECK(length(btrim(bt_id))>0),role text NOT NULL CHECK(role IN('OUT','RETURN')),
 created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(payout_id,bt_id),UNIQUE(payout_id,bt_id,role)
);
CREATE TABLE platform.payout_case (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),payout_id uuid NOT NULL REFERENCES platform.payout(id),reason text NOT NULL,
 evidence jsonb NOT NULL CHECK(jsonb_typeof(evidence)='object'),created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(payout_id,reason)
);
ALTER TABLE platform.balance_transaction ADD COLUMN payout_id uuid,
 ADD FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,payout_id) REFERENCES platform.payout(organization_id,environment_id,provider_account_id,livemode,id);
CREATE TABLE platform.payout_fact (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),payout_id uuid NOT NULL,balance_transaction_id uuid NOT NULL UNIQUE,journal_id uuid NOT NULL UNIQUE,
 kind text NOT NULL CHECK(kind IN('OUT','RETURN')),principal_minor bigint NOT NULL CHECK(principal_minor>0),transit_delta bigint NOT NULL,
 returned_delta bigint NOT NULL CHECK(returned_delta>=0),suspense_delta bigint NOT NULL CHECK(suspense_delta>=0),fee_minor bigint NOT NULL DEFAULT 0,
 organization_id uuid NOT NULL,environment_id uuid NOT NULL,currency text NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 CHECK(kind='OUT' AND transit_delta=principal_minor AND returned_delta=0 AND suspense_delta=0 OR kind='RETURN' AND transit_delta=-returned_delta AND returned_delta::numeric+suspense_delta=principal_minor),
 FOREIGN KEY(balance_transaction_id) REFERENCES platform.balance_transaction(id),
 FOREIGN KEY(organization_id,environment_id,currency,payout_id) REFERENCES platform.payout(organization_id,environment_id,currency,id),
 FOREIGN KEY(organization_id,environment_id,currency,journal_id) REFERENCES platform.journal(organization_id,environment_id,currency,id)
);
CREATE UNIQUE INDEX payout_one_outgoing ON platform.payout_fact(payout_id) WHERE kind='OUT';
CREATE TABLE platform.bank_statement (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),organization_id uuid NOT NULL,environment_id uuid NOT NULL,provider_account_id uuid NOT NULL,
 livemode boolean NOT NULL CHECK(NOT livemode),currency text NOT NULL,bank_account_ref text NOT NULL CHECK(length(btrim(bank_account_ref))>0),
 fixture_id text NOT NULL CHECK(length(btrim(fixture_id))>0),coverage_from timestamptz NOT NULL,coverage_until timestamptz NOT NULL,complete boolean NOT NULL,
 evidence_type text NOT NULL CHECK(evidence_type='SIMULATED_BANK_EVIDENCE'),payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object'),digest text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),CHECK(coverage_from<coverage_until),
 UNIQUE(organization_id,environment_id,provider_account_id,livemode,bank_account_ref,fixture_id),
 UNIQUE(organization_id,environment_id,provider_account_id,livemode,currency,bank_account_ref,id),
 FOREIGN KEY(organization_id,environment_id,provider_account_id) REFERENCES platform.provider_account(organization_id,environment_id,id),
 FOREIGN KEY(organization_id,environment_id,livemode) REFERENCES platform.environment_config(organization_id,id,livemode),
 FOREIGN KEY(organization_id,environment_id,currency) REFERENCES platform.org_currency_enabled(organization_id,environment_id,currency)
);
CREATE TABLE platform.bank_statement_line (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),statement_id uuid NOT NULL,organization_id uuid NOT NULL,environment_id uuid NOT NULL,provider_account_id uuid NOT NULL,
 livemode boolean NOT NULL CHECK(NOT livemode),currency text NOT NULL,bank_account_ref text NOT NULL,external_line_id text NOT NULL CHECK(length(btrim(external_line_id))>0),
 kind text NOT NULL CHECK(kind IN('CREDIT','RETURN')),booked_at timestamptz NOT NULL,principal_minor bigint NOT NULL CHECK(principal_minor>0),
 fee_minor bigint NOT NULL DEFAULT 0 CHECK(fee_minor>=0 AND fee_minor<=principal_minor),payout_references text[] NOT NULL CHECK(cardinality(payout_references)>0),
 allocated_minor bigint NOT NULL DEFAULT 0 CHECK(allocated_minor>=0 AND allocated_minor<=principal_minor),
 fee_allocated_minor bigint NOT NULL DEFAULT 0 CHECK(fee_allocated_minor>=0 AND fee_allocated_minor<=fee_minor),
 created_at timestamptz NOT NULL DEFAULT now(),CHECK(kind='CREDIT' OR fee_minor=0),
 UNIQUE(organization_id,environment_id,provider_account_id,livemode,bank_account_ref,external_line_id),
 UNIQUE(organization_id,environment_id,currency,id),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,currency,bank_account_ref,statement_id)
 REFERENCES platform.bank_statement(organization_id,environment_id,provider_account_id,livemode,currency,bank_account_ref,id)
);
CREATE TABLE platform.payout_bank_match (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),payout_id uuid NOT NULL,line_id uuid NOT NULL,organization_id uuid NOT NULL,environment_id uuid NOT NULL,currency text NOT NULL,
 kind text NOT NULL CHECK(kind IN('MATCH','CLAWBACK')),principal_minor bigint NOT NULL CHECK(principal_minor>0),fee_minor bigint NOT NULL CHECK(fee_minor>=0 AND fee_minor<=principal_minor),
 journal_id uuid NOT NULL UNIQUE,created_at timestamptz NOT NULL DEFAULT now(),CHECK(kind='MATCH' OR fee_minor=0),UNIQUE(payout_id,line_id,kind),
 FOREIGN KEY(organization_id,environment_id,currency,payout_id) REFERENCES platform.payout(organization_id,environment_id,currency,id),
 FOREIGN KEY(organization_id,environment_id,currency,line_id) REFERENCES platform.bank_statement_line(organization_id,environment_id,currency,id),
 FOREIGN KEY(organization_id,environment_id,currency,journal_id) REFERENCES platform.journal(organization_id,environment_id,currency,id)
);

CREATE FUNCTION platform.pr14_identity_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Immutable payout or bank identity' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='payout' THEN
  IF (NEW.id,NEW.organization_id,NEW.environment_id,NEW.provider_account_id,NEW.livemode,NEW.provider_payout_id,NEW.currency,NEW.amount_minor,NEW.automatic)
  IS DISTINCT FROM (OLD.id,OLD.organization_id,OLD.environment_id,OLD.provider_account_id,OLD.livemode,OLD.provider_payout_id,OLD.currency,OLD.amount_minor,OLD.automatic)
  THEN RAISE EXCEPTION 'Immutable payout identity' USING ERRCODE='23514'; END IF;
 ELSE
  IF (to_jsonb(NEW)-ARRAY['allocated_minor','fee_allocated_minor']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['allocated_minor','fee_allocated_minor'])
  THEN RAISE EXCEPTION 'Immutable bank line identity' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER payout_identity BEFORE UPDATE OR DELETE ON platform.payout FOR EACH ROW EXECUTE FUNCTION platform.pr14_identity_guard();
CREATE TRIGGER bank_line_identity BEFORE UPDATE OR DELETE ON platform.bank_statement_line FOR EACH ROW EXECUTE FUNCTION platform.pr14_identity_guard();
CREATE TRIGGER immutable_payout_observation BEFORE UPDATE OR DELETE ON platform.payout_observation FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence();
CREATE TRIGGER immutable_payout_link BEFORE UPDATE OR DELETE ON platform.payout_bt_link FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence();
CREATE TRIGGER immutable_payout_case BEFORE UPDATE OR DELETE ON platform.payout_case FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence();
CREATE TRIGGER immutable_payout_fact BEFORE UPDATE OR DELETE ON platform.payout_fact FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence();
CREATE TRIGGER immutable_bank_statement BEFORE UPDATE OR DELETE ON platform.bank_statement FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence();
CREATE TRIGGER immutable_payout_bank_match BEFORE UPDATE OR DELETE ON platform.payout_bank_match FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence();
CREATE FUNCTION platform.pr14_bt_link_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
BEGIN
 IF TG_OP='UPDATE' AND OLD.journal_id IS NOT NULL AND NEW.payout_id IS DISTINCT FROM OLD.payout_id
 THEN RAISE EXCEPTION 'Immutable posted payout association' USING ERRCODE='23514'; END IF;
 IF NEW.payout_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM platform.payout p WHERE p.id=NEW.payout_id AND p.currency=NEW.currency AND p.provider_payout_id=NEW.source_object_id AND NEW.source_object_type='payout' AND NEW.payment_id IS NULL AND NEW.refund_id IS NULL AND NEW.dispute_id IS NULL)
 THEN RAISE EXCEPTION 'Payout BT source mismatch' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER payout_bt_link_guard BEFORE INSERT OR UPDATE ON platform.balance_transaction FOR EACH ROW EXECUTE FUNCTION platform.pr14_bt_link_guard();

-- This is independent of the runtime posting function and checks actual sealed account movements.
CREATE FUNCTION platform.pr14_journal_amount(journal_arg uuid,purpose_arg text) RETURNS numeric LANGUAGE sql STABLE SET search_path=pg_catalog,platform AS $$
 SELECT coalesce(sum(CASE WHEN e.side='DEBIT' THEN e.amount_minor::numeric ELSE -e.amount_minor::numeric END),0)
 FROM platform.journal_entry e JOIN platform.ledger_account a ON a.id=e.ledger_account_id WHERE e.journal_id=journal_arg AND a.account_purpose=purpose_arg
$$;
CREATE FUNCTION platform.pr14_history_guard() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,platform AS $$
DECLARE p platform.payout%ROWTYPE;b platform.balance_transaction%ROWTYPE;l platform.bank_statement_line%ROWTYPE;e platform.financial_event%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='payout' THEN SELECT * INTO STRICT p FROM platform.payout WHERE id=NEW.id;
 ELSE SELECT * INTO STRICT p FROM platform.payout WHERE id=NEW.payout_id;
  SELECT ev.* INTO STRICT e FROM platform.journal j JOIN platform.financial_event ev ON ev.id=j.financial_event_id WHERE j.id=NEW.journal_id AND j.sealed_at IS NOT NULL;
  IF e.organization_id<>p.organization_id OR e.environment_id<>p.environment_id OR e.currency<>p.currency OR e.provider_account_id IS DISTINCT FROM p.provider_account_id
  OR EXISTS(SELECT 1 FROM platform.journal_entry je JOIN platform.ledger_account a ON a.id=je.ledger_account_id WHERE je.journal_id=NEW.journal_id AND a.source_domain='PROVIDER' AND (a.provider_account_id<>p.provider_account_id OR a.balance_domain<>'PAYMENTS'))
  THEN RAISE EXCEPTION 'Payout journal scope mismatch' USING ERRCODE='23514'; END IF;
  IF TG_TABLE_NAME='payout_fact' THEN
   SELECT * INTO STRICT b FROM platform.balance_transaction WHERE id=NEW.balance_transaction_id;
   IF b.payout_id IS DISTINCT FROM p.id OR b.journal_id IS DISTINCT FROM NEW.journal_id OR b.state<>'POSTED' OR e.event_key<>'bt:'||p.provider_account_id||':'||p.livemode||':'||b.bt_id
    OR e.source_domain<>'PROVIDER' OR e.source_ref<>b.bt_id OR b.source_object_id<>p.provider_payout_id OR b.source_object_type<>'payout' OR b.balance_domain<>'PAYMENTS'
    OR b.amount_minor::numeric<>(CASE WHEN NEW.kind='OUT' THEN -NEW.principal_minor::numeric ELSE NEW.principal_minor::numeric END) OR b.fee_minor<>NEW.fee_minor
    OR NOT EXISTS(SELECT 1 FROM platform.payout_bt_link WHERE payout_id=p.id AND bt_id=b.bt_id AND role=CASE WHEN NEW.kind='OUT' THEN 'OUT' ELSE 'RETURN' END)
    OR NEW.kind='OUT' AND (b.business_type<>'payout' OR NEW.principal_minor<>p.amount_minor)
    OR NEW.kind='RETURN' AND b.business_type NOT IN('payout_failure','payout_cancel')
    OR platform.pr14_journal_amount(NEW.journal_id,'PSP_FUNDS')<>b.net_minor
    OR platform.pr14_journal_amount(NEW.journal_id,'PSP_FEE_EXPENSE')<>NEW.fee_minor
    OR platform.pr14_journal_amount(NEW.journal_id,'BANK_PAYOUT_IN_TRANSIT')<>NEW.transit_delta
    OR platform.pr14_journal_amount(NEW.journal_id,'SUSPENSE')<>-NEW.suspense_delta::numeric
    OR EXISTS(SELECT 1 FROM platform.journal_entry je JOIN platform.ledger_account a ON a.id=je.ledger_account_id WHERE je.journal_id=NEW.journal_id AND a.account_purpose NOT IN('PSP_FUNDS','PSP_FEE_EXPENSE','BANK_PAYOUT_IN_TRANSIT','SUSPENSE'))
   THEN RAISE EXCEPTION 'Payout financial facts require exact linked sealed BT journal' USING ERRCODE='23514'; END IF;
  ELSE
   SELECT * INTO STRICT l FROM platform.bank_statement_line WHERE id=NEW.line_id;
   IF l.organization_id<>p.organization_id OR l.environment_id<>p.environment_id OR l.provider_account_id<>p.provider_account_id OR l.livemode<>p.livemode OR l.currency<>p.currency
    OR NOT(p.provider_payout_id=ANY(l.payout_references)) OR p.arrival_date IS NULL OR (l.booked_at AT TIME ZONE 'UTC')::date<(p.arrival_date AT TIME ZONE 'UTC')::date-7 OR (l.booked_at AT TIME ZONE 'UTC')::date>(p.arrival_date AT TIME ZONE 'UTC')::date+7
    OR e.source_domain<>'SIMULATED_BANK'
    OR NEW.kind='MATCH' AND (l.kind<>'CREDIT' OR e.event_key<>'bank.match:'||p.id||':'||l.id
     OR platform.pr14_journal_amount(NEW.journal_id,'BANK_CASH')<>NEW.principal_minor-NEW.fee_minor
     OR platform.pr14_journal_amount(NEW.journal_id,'BANK_FEE_EXPENSE')<>NEW.fee_minor
     OR platform.pr14_journal_amount(NEW.journal_id,'BANK_PAYOUT_IN_TRANSIT')<>-NEW.principal_minor::numeric
     OR EXISTS(SELECT 1 FROM platform.journal_entry je JOIN platform.ledger_account a ON a.id=je.ledger_account_id WHERE je.journal_id=NEW.journal_id AND a.account_purpose NOT IN('BANK_CASH','BANK_FEE_EXPENSE','BANK_PAYOUT_IN_TRANSIT')))
    OR NEW.kind='CLAWBACK' AND (l.kind<>'RETURN' OR e.event_key<>'bank.clawback:'||p.id||':'||l.id||':1'
     OR platform.pr14_journal_amount(NEW.journal_id,'BANK_CASH')<>-NEW.principal_minor::numeric
     OR platform.pr14_journal_amount(NEW.journal_id,'SUSPENSE')<>NEW.principal_minor
     OR EXISTS(SELECT 1 FROM platform.journal_entry je JOIN platform.ledger_account a ON a.id=je.ledger_account_id WHERE je.journal_id=NEW.journal_id AND a.account_purpose NOT IN('BANK_CASH','SUSPENSE')))
   THEN RAISE EXCEPTION 'Bank allocation requires scoped simulated evidence and exact sealed journal' USING ERRCODE='23514'; END IF;
  END IF;
 END IF;
 IF p.outgoing_minor<>(SELECT coalesce(sum(principal_minor),0) FROM platform.payout_fact WHERE payout_id=p.id AND kind='OUT')
 OR p.transit_minor<>(SELECT coalesce(sum(transit_delta),0) FROM platform.payout_fact WHERE payout_id=p.id)-(SELECT coalesce(sum(principal_minor),0) FROM platform.payout_bank_match WHERE payout_id=p.id AND kind='MATCH')
 OR p.bank_confirmed_minor<>(SELECT coalesce(sum(CASE WHEN kind='MATCH' THEN principal_minor::numeric ELSE -principal_minor::numeric END),0) FROM platform.payout_bank_match WHERE payout_id=p.id)
 OR p.returned_minor<>(SELECT coalesce(sum(returned_delta),0) FROM platform.payout_fact WHERE payout_id=p.id)+(SELECT coalesce(sum(principal_minor),0) FROM platform.payout_bank_match WHERE payout_id=p.id AND kind='CLAWBACK')
 OR p.suspense_minor<>(SELECT coalesce(sum(suspense_delta),0) FROM platform.payout_fact WHERE payout_id=p.id)-(SELECT coalesce(sum(principal_minor),0) FROM platform.payout_bank_match WHERE payout_id=p.id AND kind='CLAWBACK')
 OR p.psp_fee_minor<>(SELECT coalesce(sum(fee_minor),0) FROM platform.payout_fact WHERE payout_id=p.id)
 THEN RAISE EXCEPTION 'Payout counters require immutable sealed financial history' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER payout_history AFTER INSERT OR UPDATE ON platform.payout DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.pr14_history_guard();
CREATE CONSTRAINT TRIGGER payout_fact_history AFTER INSERT ON platform.payout_fact DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.pr14_history_guard();
CREATE CONSTRAINT TRIGGER payout_bank_history AFTER INSERT ON platform.payout_bank_match DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.pr14_history_guard();
CREATE FUNCTION platform.pr14_bank_line_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
DECLARE l platform.bank_statement_line%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='bank_statement_line' THEN SELECT * INTO STRICT l FROM platform.bank_statement_line WHERE id=NEW.id;
 ELSE SELECT * INTO STRICT l FROM platform.bank_statement_line WHERE id=NEW.line_id; END IF;
 IF l.allocated_minor<>(SELECT coalesce(sum(principal_minor),0) FROM platform.payout_bank_match WHERE line_id=l.id)
 OR l.fee_allocated_minor<>(SELECT coalesce(sum(fee_minor),0) FROM platform.payout_bank_match WHERE line_id=l.id)
 OR l.fee_allocated_minor<>floor(l.fee_minor::numeric*l.allocated_minor/l.principal_minor)
 OR NOT EXISTS(SELECT 1 FROM platform.bank_statement s WHERE s.id=l.statement_id AND l.booked_at>=s.coverage_from AND l.booked_at<s.coverage_until)
 THEN RAISE EXCEPTION 'Bank counters require exact immutable allocation and coverage' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER bank_line_history AFTER INSERT OR UPDATE ON platform.bank_statement_line DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.pr14_bank_line_guard();
CREATE CONSTRAINT TRIGGER bank_match_line_history AFTER INSERT ON platform.payout_bank_match DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.pr14_bank_line_guard();

-- Account provisioning is deliberately narrowed to the derived payout scope and fixed purposes.
CREATE FUNCTION platform.ensure_payout_accounts(payout_arg uuid) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,platform AS $$
BEGIN
 INSERT INTO platform.ledger_account(organization_id,environment_id,currency,account_purpose,source_domain,provider_account_id,balance_domain)
 SELECT p.organization_id,p.environment_id,p.currency,purpose,'PROVIDER',p.provider_account_id,'PAYMENTS' FROM platform.payout p
 CROSS JOIN(VALUES('PSP_FUNDS'),('PSP_FEE_EXPENSE'),('BANK_PAYOUT_IN_TRANSIT'),('SUSPENSE')) purposes(purpose) WHERE p.id=payout_arg ORDER BY purpose ON CONFLICT DO NOTHING;
 INSERT INTO platform.ledger_account(organization_id,environment_id,currency,account_purpose,source_domain)
 SELECT p.organization_id,p.environment_id,p.currency,purpose,'SIMULATED_BANK' FROM platform.payout p
 CROSS JOIN(VALUES('BANK_CASH'),('BANK_FEE_EXPENSE')) purposes(purpose) WHERE p.id=payout_arg ORDER BY purpose ON CONFLICT DO NOTHING;
END $$;
CREATE FUNCTION platform.lock_payout_accounts(payout_arg uuid) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,platform AS $$
BEGIN
 PERFORM a.id FROM platform.ledger_account a JOIN platform.payout p ON p.organization_id=a.organization_id AND p.environment_id=a.environment_id AND p.currency=a.currency
 WHERE p.id=payout_arg AND ((a.source_domain='PROVIDER' AND a.provider_account_id=p.provider_account_id AND a.balance_domain='PAYMENTS' AND a.account_purpose IN('PSP_FUNDS','PSP_FEE_EXPENSE','BANK_PAYOUT_IN_TRANSIT','SUSPENSE'))
 OR (a.source_domain='SIMULATED_BANK' AND a.provider_account_id IS NULL AND a.account_purpose IN('BANK_CASH','BANK_FEE_EXPENSE')))
 ORDER BY a.id FOR UPDATE OF a;
END $$;
REVOKE ALL ON FUNCTION platform.pr14_identity_guard(),platform.pr14_bt_link_guard(),platform.pr14_journal_amount(uuid,text),platform.pr14_history_guard(),platform.pr14_bank_line_guard(),platform.ensure_payout_accounts(uuid),platform.lock_payout_accounts(uuid) FROM PUBLIC;
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='payments_app') THEN
 GRANT SELECT,INSERT,UPDATE ON platform.payout TO payments_app;
 GRANT SELECT,INSERT ON platform.payout_observation,platform.payout_bt_link,platform.payout_case,platform.payout_fact,platform.bank_statement,platform.bank_statement_line,platform.payout_bank_match TO payments_app;
 GRANT UPDATE(allocated_minor,fee_allocated_minor) ON platform.bank_statement_line TO payments_app;
 GRANT EXECUTE ON FUNCTION platform.ensure_payout_accounts(uuid),platform.lock_payout_accounts(uuid) TO payments_app;
END IF; END $$;
