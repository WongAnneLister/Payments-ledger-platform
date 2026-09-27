-- Ambiguous legacy ownership must be investigated explicitly. Flyway rolls the whole migration back;
-- never pick an arbitrary owner or delete payment evidence during an upgrade.
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM platform.payment WHERE payment_intent_id IS NOT NULL GROUP BY organization_id,environment_id,provider_account_id,payment_intent_id HAVING count(*)>1) THEN
  RAISE EXCEPTION 'PR08 requires resolution of duplicate scoped provider object ownership before upgrade';
 END IF;
END $$;
CREATE UNIQUE INDEX payment_provider_intent_identity ON platform.payment(organization_id,environment_id,provider_account_id,payment_intent_id) WHERE payment_intent_id IS NOT NULL;
ALTER TABLE platform.payment ADD COLUMN last_query_confirmed_at timestamptz, ADD COLUMN last_webhook_created_at timestamptz;
UPDATE platform.payment SET last_query_confirmed_at=last_provider_confirmed_at WHERE last_provider_confirmed_at IS NOT NULL;
CREATE TABLE platform.webhook_inbox (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, environment_id uuid NOT NULL, provider_account_id uuid NOT NULL,
 livemode boolean NOT NULL CHECK(NOT livemode), provider_account_ref text NOT NULL,
 event_id text NOT NULL, event_type text NOT NULL, object_id text NOT NULL, object_type text NOT NULL,
 event_created_at timestamptz NOT NULL, received_at timestamptz NOT NULL, verified_at timestamptz NOT NULL,
 api_version text NOT NULL, raw_digest text NOT NULL CHECK(raw_digest ~ '^[a-f0-9]{64}$'),
 object_snapshot jsonb NOT NULL CHECK(jsonb_typeof(object_snapshot)='object'),
 status text NOT NULL DEFAULT 'READY' CHECK(status IN ('READY','RUNNING','WAITING','DONE')),
 available_at timestamptz NOT NULL, lease_token uuid, lease_until timestamptz,
 attempt_count integer NOT NULL DEFAULT 0 CHECK(attempt_count>=0), processed_at timestamptz, reason text,
 FOREIGN KEY(organization_id,environment_id,provider_account_id) REFERENCES platform.provider_account(organization_id,environment_id,id),
 UNIQUE(organization_id,environment_id,provider_account_id,livemode,event_id),
 UNIQUE(organization_id,environment_id,provider_account_id,id)
);
CREATE INDEX webhook_due ON platform.webhook_inbox(available_at,id) WHERE status<>'DONE';
ALTER TABLE platform.payment ADD CONSTRAINT payment_provider_scope_identity UNIQUE(organization_id,environment_id,provider_account_id,id);
CREATE TABLE platform.normalized_observation (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, environment_id uuid NOT NULL, provider_account_id uuid NOT NULL,
 fact_key text NOT NULL, fact_kind text NOT NULL, object_id text NOT NULL, payment_id uuid,
 fact jsonb NOT NULL CHECK(jsonb_typeof(fact)='object'),
 first_source text NOT NULL CHECK(first_source IN ('QUERY','WEBHOOK')), last_source text NOT NULL CHECK(last_source IN ('QUERY','WEBHOOK')),
 first_confirmed_at timestamptz NOT NULL, last_confirmed_at timestamptz NOT NULL,
 FOREIGN KEY(organization_id,environment_id,provider_account_id) REFERENCES platform.provider_account(organization_id,environment_id,id),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,payment_id) REFERENCES platform.payment(organization_id,environment_id,provider_account_id,id),
 UNIQUE(organization_id,environment_id,provider_account_id,fact_key)
);
CREATE TABLE platform.dependency_wait (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, environment_id uuid NOT NULL, provider_account_id uuid NOT NULL,
 inbox_id uuid NOT NULL UNIQUE, dependency_type text NOT NULL, dependency_id text, reason text NOT NULL,
 first_seen_at timestamptz NOT NULL, last_attempt_at timestamptz NOT NULL, resolved_at timestamptz,
 FOREIGN KEY(organization_id,environment_id,provider_account_id,inbox_id) REFERENCES platform.webhook_inbox(organization_id,environment_id,provider_account_id,id)
);
CREATE FUNCTION platform.pr08_evidence_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='webhook_inbox' AND (to_jsonb(NEW)-ARRAY['status','available_at','lease_token','lease_until','attempt_count','processed_at','reason']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','available_at','lease_token','lease_until','attempt_count','processed_at','reason']) THEN
  RAISE EXCEPTION 'Immutable webhook evidence' USING ERRCODE='23514';
 ELSIF TG_TABLE_NAME='normalized_observation' AND (to_jsonb(NEW)-ARRAY['last_source','last_confirmed_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['last_source','last_confirmed_at']) THEN
  RAISE EXCEPTION 'Immutable normalized fact' USING ERRCODE='23514';
 ELSIF TG_TABLE_NAME='dependency_wait' AND (to_jsonb(NEW)-ARRAY['dependency_type','dependency_id','reason','last_attempt_at','resolved_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['dependency_type','dependency_id','reason','last_attempt_at','resolved_at']) THEN
  RAISE EXCEPTION 'Immutable dependency identity' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER webhook_evidence_immutable BEFORE UPDATE ON platform.webhook_inbox FOR EACH ROW EXECUTE FUNCTION platform.pr08_evidence_guard();
CREATE TRIGGER normalized_fact_immutable BEFORE UPDATE ON platform.normalized_observation FOR EACH ROW EXECUTE FUNCTION platform.pr08_evidence_guard();
CREATE TRIGGER dependency_identity_immutable BEFORE UPDATE ON platform.dependency_wait FOR EACH ROW EXECUTE FUNCTION platform.pr08_evidence_guard();
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='payments_app') THEN
  GRANT SELECT,INSERT,UPDATE ON platform.webhook_inbox,platform.normalized_observation,platform.dependency_wait TO payments_app;
 END IF;
END $$;
