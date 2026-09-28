-- PR10: provider evidence and import progress are durable before ledger replay.
CREATE TABLE platform.ingestion_policy (
 organization_id uuid NOT NULL, environment_id uuid NOT NULL, provider_account_id uuid PRIMARY KEY, livemode boolean NOT NULL,
 opening_mode text NOT NULL CHECK(opening_mode IN ('ZERO','VERIFIED_CUTOFF')),
 cutoff_at timestamptz, evidence_ref text, evidence_digest text,
 business_zone text NOT NULL DEFAULT 'UTC',
 CHECK((opening_mode='ZERO' AND cutoff_at IS NULL AND evidence_ref IS NULL AND evidence_digest IS NULL)
    OR (opening_mode='VERIFIED_CUTOFF' AND cutoff_at IS NOT NULL AND evidence_ref IS NOT NULL AND evidence_digest IS NOT NULL AND length(btrim(evidence_ref))>0 AND evidence_digest ~ '^[0-9a-f]{64}$')),
 FOREIGN KEY(organization_id,environment_id,provider_account_id) REFERENCES platform.provider_account(organization_id,environment_id,id),
 FOREIGN KEY(organization_id,environment_id,livemode) REFERENCES platform.environment_config(organization_id,id,livemode),
 UNIQUE(organization_id,environment_id,provider_account_id,livemode),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE platform.import_window (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, environment_id uuid NOT NULL,
 provider_account_id uuid NOT NULL, livemode boolean NOT NULL,
 created_gte timestamptz NOT NULL, created_lt timestamptz NOT NULL, purpose text NOT NULL CHECK(purpose IN ('OVERLAP','BACKFILL')),
 state text NOT NULL DEFAULT 'INCOMPLETE' CHECK(state IN ('INCOMPLETE','COMPLETE')), last_error text,
 completed_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), version bigint NOT NULL DEFAULT 0,
 CHECK(created_gte<created_lt), CHECK((state='COMPLETE')=(completed_at IS NOT NULL)),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode) REFERENCES platform.ingestion_policy(organization_id,environment_id,provider_account_id,livemode),
 UNIQUE(organization_id,environment_id,provider_account_id,livemode,id)
);
CREATE TABLE platform.import_cursor (
 window_id uuid PRIMARY KEY REFERENCES platform.import_window(id), next_cursor text,
 api_version text, version bigint NOT NULL DEFAULT 0, updated_at timestamptz NOT NULL DEFAULT now()
);
-- Generalize the existing append-only evidence store without relaxing PAYMENT evidence requirements.
ALTER TABLE platform.provider_observation
 ALTER COLUMN application_id DROP NOT NULL, ALTER COLUMN currency DROP NOT NULL,
 ALTER COLUMN operation_id DROP NOT NULL, ALTER COLUMN payment_id DROP NOT NULL,
 ADD COLUMN observation_domain text NOT NULL DEFAULT 'PAYMENT' CHECK(observation_domain IN ('PAYMENT','FINANCIAL')),
 ADD COLUMN livemode boolean, ADD COLUMN import_window_id uuid, ADD COLUMN fetched_at timestamptz,
 ADD CONSTRAINT observation_domain_scope CHECK(
  (observation_domain='PAYMENT' AND application_id IS NOT NULL AND currency IS NOT NULL AND operation_id IS NOT NULL AND payment_id IS NOT NULL AND import_window_id IS NULL AND livemode IS NULL)
  OR (observation_domain='FINANCIAL' AND application_id IS NULL AND operation_id IS NULL AND payment_id IS NULL AND livemode IS NOT NULL AND fetched_at IS NOT NULL)),
 ADD FOREIGN KEY(organization_id,environment_id,livemode) REFERENCES platform.environment_config(organization_id,id,livemode),
 ADD FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,import_window_id) REFERENCES platform.import_window(organization_id,environment_id,provider_account_id,livemode,id),
 ADD UNIQUE(organization_id,environment_id,provider_account_id,livemode,id);
CREATE TABLE platform.import_page (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), window_id uuid NOT NULL REFERENCES platform.import_window(id),
 request_cursor text NOT NULL, next_cursor text, terminal boolean NOT NULL,
 observation_id uuid NOT NULL REFERENCES platform.provider_observation(id), bt_ids text[] NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(window_id,request_cursor),
 CHECK((terminal AND next_cursor IS NULL) OR (NOT terminal AND next_cursor IS NOT NULL))
);
CREATE TABLE platform.balance_transaction (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, environment_id uuid NOT NULL, provider_account_id uuid NOT NULL, livemode boolean NOT NULL,
 bt_id text NOT NULL CHECK(length(btrim(bt_id))>0), amount_minor bigint NOT NULL, fee_minor bigint NOT NULL, net_minor bigint NOT NULL,
 currency text NOT NULL CHECK(currency ~ '^[A-Z]{3}$'), source_object_id text, source_object_type text, business_type text,
 balance_domain text NOT NULL, provider_created_at timestamptz NOT NULL, available_on timestamptz,
 provider_status text NOT NULL, raw_digest text, api_version text NOT NULL, fetched_at timestamptz NOT NULL,
 immutable_fact jsonb NOT NULL, fact jsonb NOT NULL,
 first_observation_id uuid NOT NULL,
 state text NOT NULL DEFAULT 'PENDING_EVIDENCE' CHECK(state IN ('PENDING_EVIDENCE','POSTED','QUARANTINED','EXCLUDED','ZERO')),
 reason text, journal_id uuid, payment_id uuid,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), version bigint NOT NULL DEFAULT 0,
 CHECK(amount_minor::numeric-fee_minor::numeric=net_minor), CHECK((state='POSTED')=(journal_id IS NOT NULL)),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode) REFERENCES platform.ingestion_policy(organization_id,environment_id,provider_account_id,livemode),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,first_observation_id) REFERENCES platform.provider_observation(organization_id,environment_id,provider_account_id,livemode,id),
 FOREIGN KEY(organization_id,environment_id,currency,journal_id) REFERENCES platform.journal(organization_id,environment_id,currency,id),
 FOREIGN KEY(payment_id) REFERENCES platform.payment(id),
 UNIQUE(organization_id,provider_account_id,livemode,bt_id),
 UNIQUE(organization_id,environment_id,provider_account_id,livemode,id), UNIQUE(journal_id)
);
CREATE INDEX pending_financial_replay ON platform.balance_transaction(created_at) WHERE state='PENDING_EVIDENCE';
CREATE TABLE platform.quarantine_observation (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, environment_id uuid NOT NULL, provider_account_id uuid NOT NULL, livemode boolean NOT NULL,
 observation_id uuid NOT NULL, balance_transaction_id uuid, reason text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,observation_id) REFERENCES platform.provider_observation(organization_id,environment_id,provider_account_id,livemode,id),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,balance_transaction_id) REFERENCES platform.balance_transaction(organization_id,environment_id,provider_account_id,livemode,id)
);
CREATE TABLE platform.bt_evidence_conflict (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, environment_id uuid NOT NULL, provider_account_id uuid NOT NULL, livemode boolean NOT NULL,
 balance_transaction_id uuid NOT NULL, observation_id uuid NOT NULL, existing_fact jsonb NOT NULL, incoming_fact jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,balance_transaction_id) REFERENCES platform.balance_transaction(organization_id,environment_id,provider_account_id,livemode,id),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,observation_id) REFERENCES platform.provider_observation(organization_id,environment_id,provider_account_id,livemode,id)
);
CREATE FUNCTION platform.pr10_immutable_evidence() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
BEGIN RAISE EXCEPTION 'Immutable financial evidence' USING ERRCODE='23514'; END $$;
CREATE TRIGGER immutable_import_page BEFORE UPDATE OR DELETE ON platform.import_page FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence();
CREATE TRIGGER immutable_quarantine BEFORE UPDATE OR DELETE ON platform.quarantine_observation FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence();
CREATE TRIGGER immutable_bt_conflict BEFORE UPDATE OR DELETE ON platform.bt_evidence_conflict FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence();
CREATE FUNCTION platform.pr10_bt_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Immutable balance transaction' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND ((NEW.id,NEW.organization_id,NEW.environment_id,NEW.provider_account_id,NEW.livemode,NEW.bt_id,NEW.amount_minor,NEW.fee_minor,NEW.net_minor,NEW.currency,NEW.source_object_id,NEW.source_object_type,NEW.business_type,NEW.balance_domain,NEW.provider_created_at,NEW.immutable_fact,NEW.fact,NEW.first_observation_id,NEW.api_version)
 IS DISTINCT FROM (OLD.id,OLD.organization_id,OLD.environment_id,OLD.provider_account_id,OLD.livemode,OLD.bt_id,OLD.amount_minor,OLD.fee_minor,OLD.net_minor,OLD.currency,OLD.source_object_id,OLD.source_object_type,OLD.business_type,OLD.balance_domain,OLD.provider_created_at,OLD.immutable_fact,OLD.fact,OLD.first_observation_id,OLD.api_version)
 OR OLD.journal_id IS NOT NULL AND (NEW.journal_id,NEW.payment_id,NEW.state) IS DISTINCT FROM (OLD.journal_id,OLD.payment_id,OLD.state))
 THEN RAISE EXCEPTION 'Immutable balance transaction facts or posted linkage' USING ERRCODE='23514'; END IF;
 IF NEW.payment_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM platform.payment p WHERE p.id=NEW.payment_id AND p.organization_id=NEW.organization_id AND p.environment_id=NEW.environment_id AND p.provider_account_id=NEW.provider_account_id AND p.currency=NEW.currency) THEN
 RAISE EXCEPTION 'Payment scope mismatch' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER balance_transaction_guard BEFORE INSERT OR UPDATE OR DELETE ON platform.balance_transaction FOR EACH ROW EXECUTE FUNCTION platform.pr10_bt_guard();
CREATE FUNCTION platform.pr10_policy_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM platform.import_window w JOIN platform.import_page p ON p.window_id=w.id WHERE w.provider_account_id=OLD.provider_account_id)
 OR EXISTS(SELECT 1 FROM platform.balance_transaction WHERE provider_account_id=OLD.provider_account_id) THEN
 RAISE EXCEPTION 'Opening policy frozen after accepted history' USING ERRCODE='23514'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END $$;
CREATE TRIGGER frozen_ingestion_policy BEFORE UPDATE OR DELETE ON platform.ingestion_policy FOR EACH ROW EXECUTE FUNCTION platform.pr10_policy_guard();
CREATE FUNCTION platform.pr10_window_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
BEGIN
 IF (NEW.organization_id,NEW.environment_id,NEW.provider_account_id,NEW.livemode,NEW.created_gte,NEW.created_lt,NEW.purpose) IS DISTINCT FROM (OLD.organization_id,OLD.environment_id,OLD.provider_account_id,OLD.livemode,OLD.created_gte,OLD.created_lt,OLD.purpose)
 OR OLD.state='COMPLETE' AND NEW IS DISTINCT FROM OLD THEN RAISE EXCEPTION 'Immutable import scope or completion' USING ERRCODE='23514'; END IF;
 IF NEW.state='COMPLETE' AND NOT EXISTS(SELECT 1 FROM platform.import_page WHERE window_id=NEW.id AND terminal) THEN RAISE EXCEPTION 'Terminal accepted page required' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER import_window_guard BEFORE UPDATE ON platform.import_window FOR EACH ROW EXECUTE FUNCTION platform.pr10_window_guard();
-- SELECT FOR SHARE requires UPDATE privilege. A narrow lock function preserves read-only configuration access.
CREATE FUNCTION platform.lock_ingestion_policy(p_org uuid,p_env uuid,p_provider uuid,p_live boolean) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,platform AS $$
BEGIN
 PERFORM 1 FROM platform.ingestion_policy WHERE organization_id=p_org AND environment_id=p_env AND provider_account_id=p_provider AND livemode=p_live FOR SHARE;
 RETURN FOUND;
END $$;
REVOKE ALL ON FUNCTION platform.lock_ingestion_policy(uuid,uuid,uuid,boolean) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform.pr10_immutable_evidence(),platform.pr10_bt_guard(),platform.pr10_policy_guard(),platform.pr10_window_guard() FROM PUBLIC;
DO $$ DECLARE t text; BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='payments_app') THEN
  FOREACH t IN ARRAY ARRAY['ingestion_policy','import_window','import_cursor','import_page','balance_transaction','quarantine_observation','bt_evidence_conflict'] LOOP
   EXECUTE format('REVOKE ALL ON platform.%I FROM payments_app',t);
   EXECUTE format('GRANT SELECT ON platform.%I TO payments_app',t);
  END LOOP;
  GRANT INSERT ON platform.import_window,platform.import_cursor,platform.import_page,platform.balance_transaction,platform.quarantine_observation,platform.bt_evidence_conflict TO payments_app;
  GRANT EXECUTE ON FUNCTION platform.lock_ingestion_policy(uuid,uuid,uuid,boolean) TO payments_app;
  GRANT UPDATE ON platform.import_window,platform.import_cursor,platform.balance_transaction TO payments_app;
  REVOKE ALL ON FUNCTION platform.pr10_immutable_evidence(),platform.pr10_bt_guard(),platform.pr10_policy_guard(),platform.pr10_window_guard() FROM payments_app;
 END IF;
END $$;

-- Snapshot cutoff is inclusive: only BT created strictly AFTER it is eligible for replay.
-- No availability is inferred for an opening balance or other unclassified ledger movement.
CREATE VIEW platform.psp_availability_projection AS
WITH known AS (
 SELECT organization_id,environment_id,provider_account_id,livemode,currency,
  coalesce(sum(net_minor) FILTER(WHERE provider_status='pending'),0) AS pending_net_minor,
  coalesce(sum(net_minor) FILTER(WHERE provider_status='available'),0) AS available_net_minor,
  sum(net_minor) AS known_bt_net_minor
 FROM platform.balance_transaction WHERE state='POSTED' AND balance_domain='PAYMENTS'
 GROUP BY organization_id,environment_id,provider_account_id,livemode,currency
)
SELECT a.organization_id,a.environment_id,a.provider_account_id,p.livemode,a.currency,
 coalesce(k.pending_net_minor,0) AS pending_net_minor,coalesce(k.available_net_minor,0) AS available_net_minor,
 coalesce(k.known_bt_net_minor,0) AS known_bt_net_minor,coalesce(b.balance_minor,0) AS ledger_balance_minor,
 coalesce(b.balance_minor,0)-coalesce(k.known_bt_net_minor,0) AS unclassified_or_opening_minor
FROM platform.ledger_account a
JOIN platform.provider_account p ON p.organization_id=a.organization_id AND p.environment_id=a.environment_id AND p.id=a.provider_account_id
LEFT JOIN platform.balance_projection b ON b.ledger_account_id=a.id
LEFT JOIN known k ON k.organization_id=a.organization_id AND k.environment_id=a.environment_id AND k.provider_account_id=a.provider_account_id AND k.livemode=p.livemode AND k.currency=a.currency
WHERE a.account_purpose='PSP_FUNDS' AND a.balance_domain='PAYMENTS';

CREATE FUNCTION platform.pr10_page_scope_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM platform.provider_observation o WHERE o.id=NEW.observation_id
    AND o.import_window_id=NEW.window_id AND o.observation_domain='FINANCIAL'
    AND o.call_type='LIST_BALANCE_TRANSACTIONS' AND o.classification='SUCCESS') THEN
  RAISE EXCEPTION 'Accepted page requires successful list evidence for the same window' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER import_page_scope_guard BEFORE INSERT ON platform.import_page FOR EACH ROW EXECUTE FUNCTION platform.pr10_page_scope_guard();
CREATE FUNCTION platform.pr10_cursor_identity_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
BEGIN
 IF NEW.window_id IS DISTINCT FROM OLD.window_id OR OLD.api_version IS NOT NULL AND NEW.api_version IS DISTINCT FROM OLD.api_version THEN
  RAISE EXCEPTION 'Immutable import cursor identity and evidence version' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER import_cursor_identity_guard BEFORE UPDATE ON platform.import_cursor FOR EACH ROW EXECUTE FUNCTION platform.pr10_cursor_identity_guard();
REVOKE ALL ON FUNCTION platform.pr10_page_scope_guard(),platform.pr10_cursor_identity_guard() FROM PUBLIC;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='payments_app') THEN
  REVOKE ALL ON platform.psp_availability_projection FROM payments_app;
  GRANT SELECT ON platform.psp_availability_projection TO payments_app;
  REVOKE ALL ON FUNCTION platform.pr10_page_scope_guard(),platform.pr10_cursor_identity_guard() FROM payments_app;
 END IF;
END $$;
