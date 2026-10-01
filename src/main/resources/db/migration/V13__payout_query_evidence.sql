-- PR14 query/page evidence is independent of money posting. No guessed manual membership.
ALTER TABLE platform.payout_observation ADD UNIQUE(payout_id,id);
CREATE TABLE platform.payout_read_run (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),organization_id uuid NOT NULL,environment_id uuid NOT NULL,provider_account_id uuid NOT NULL,livemode boolean NOT NULL CHECK(NOT livemode),
 kind text NOT NULL CHECK(kind IN('PAYOUTS','MEMBERSHIP')),payout_id uuid,source_observation_id uuid,
 created_gte timestamptz NOT NULL,created_lt timestamptz NOT NULL CHECK(created_gte<created_lt),
 next_cursor text,api_version text,state text NOT NULL DEFAULT 'INCOMPLETE' CHECK(state IN('INCOMPLETE','COMPLETE')),
 last_error text,version bigint NOT NULL DEFAULT 0,updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK((kind='MEMBERSHIP')=(payout_id IS NOT NULL)),CHECK((payout_id IS NULL)=(source_observation_id IS NULL)),
 UNIQUE(organization_id,environment_id,provider_account_id,livemode,id),UNIQUE(id,payout_id),UNIQUE(payout_id,source_observation_id,created_gte,created_lt),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,payout_id) REFERENCES platform.payout(organization_id,environment_id,provider_account_id,livemode,id),
 FOREIGN KEY(payout_id,source_observation_id) REFERENCES platform.payout_observation(payout_id,id),
 FOREIGN KEY(organization_id,environment_id,provider_account_id) REFERENCES platform.provider_account(organization_id,environment_id,id),
 FOREIGN KEY(organization_id,environment_id,livemode) REFERENCES platform.environment_config(organization_id,id,livemode)
);
CREATE TABLE platform.payout_read_observation (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),organization_id uuid NOT NULL,environment_id uuid NOT NULL,provider_account_id uuid NOT NULL,livemode boolean NOT NULL CHECK(NOT livemode),
 run_id uuid REFERENCES platform.payout_read_run(id),operation text NOT NULL,object_id text,classification text NOT NULL,payload jsonb,evidence jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(run_id,id),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,run_id) REFERENCES platform.payout_read_run(organization_id,environment_id,provider_account_id,livemode,id),
 FOREIGN KEY(organization_id,environment_id,livemode) REFERENCES platform.environment_config(organization_id,id,livemode),
 FOREIGN KEY(organization_id,environment_id,provider_account_id) REFERENCES platform.provider_account(organization_id,environment_id,id)
);
CREATE TABLE platform.payout_read_page (
 run_id uuid NOT NULL REFERENCES platform.payout_read_run(id),request_cursor text NOT NULL, next_cursor text,terminal boolean NOT NULL,
 observation_id uuid NOT NULL REFERENCES platform.payout_read_observation(id),object_ids text[] NOT NULL,
 CHECK(terminal=(next_cursor IS NULL)),
 FOREIGN KEY(run_id,observation_id) REFERENCES platform.payout_read_observation(run_id,id),
 PRIMARY KEY(run_id,request_cursor)
);
CREATE TABLE platform.payout_membership (
 payout_id uuid NOT NULL REFERENCES platform.payout(id),bt_id text NOT NULL,
 run_id uuid NOT NULL REFERENCES platform.payout_read_run(id),observation_id uuid NOT NULL REFERENCES platform.payout_read_observation(id),
 fact jsonb NOT NULL,
 FOREIGN KEY(run_id,payout_id) REFERENCES platform.payout_read_run(id,payout_id),
 FOREIGN KEY(run_id,observation_id) REFERENCES platform.payout_read_observation(run_id,id),
 PRIMARY KEY(payout_id,bt_id)
);
CREATE TRIGGER immutable_payout_read_observation BEFORE UPDATE OR DELETE ON platform.payout_read_observation FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence();
CREATE TRIGGER immutable_payout_read_page BEFORE UPDATE OR DELETE ON platform.payout_read_page FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence();
CREATE TRIGGER immutable_payout_membership BEFORE UPDATE OR DELETE ON platform.payout_membership FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence();
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='payments_app') THEN
 GRANT SELECT,INSERT,UPDATE ON platform.payout_read_run TO payments_app;
 GRANT SELECT,INSERT ON platform.payout_read_observation,platform.payout_read_page,platform.payout_membership TO payments_app;
END IF; END $$;

-- Provenance identity cannot be rewritten, and only explicit automatic provider membership is allowed.
CREATE FUNCTION platform.pr14_read_provenance_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
DECLARE r platform.payout_read_run%ROWTYPE;o platform.payout_read_observation%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='payout_read_run' THEN
  IF TG_OP='UPDATE' AND (to_jsonb(NEW)-ARRAY['next_cursor','api_version','state','last_error','version','updated_at']) IS DISTINCT FROM(to_jsonb(OLD)-ARRAY['next_cursor','api_version','state','last_error','version','updated_at'])
  THEN RAISE EXCEPTION 'Immutable payout query scope and source version' USING ERRCODE='23514'; END IF;
  IF NEW.kind='MEMBERSHIP' AND NOT EXISTS(SELECT 1 FROM platform.payout p WHERE p.id=NEW.payout_id AND p.automatic)
  THEN RAISE EXCEPTION 'Manual payout cannot have official membership' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='payout_read_observation' AND NEW.run_id IS NOT NULL THEN
  SELECT * INTO STRICT r FROM platform.payout_read_run WHERE id=NEW.run_id;
  IF NEW.operation<>(CASE WHEN r.kind='MEMBERSHIP' THEN 'LIST_PAYOUT_MEMBERSHIP' ELSE 'LIST_PAYOUTS' END)
  OR r.kind='MEMBERSHIP' AND NEW.object_id IS DISTINCT FROM(SELECT provider_payout_id FROM platform.payout WHERE id=r.payout_id)
  THEN RAISE EXCEPTION 'Payout read operation/source mismatch' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='payout_membership' THEN
  SELECT * INTO STRICT o FROM platform.payout_read_observation WHERE id=NEW.observation_id;
  IF o.classification<>'SUCCESS' OR o.operation<>'LIST_PAYOUT_MEMBERSHIP'
  OR NOT EXISTS(SELECT 1 FROM jsonb_array_elements(o.payload->'items') f WHERE f->>'btId'=NEW.bt_id
    AND upper(f->>'currency')=NEW.fact->>'currency' AND f->'amountMinor'=NEW.fact->'amountMinor' AND f->'feeMinor'=NEW.fact->'feeMinor' AND f->'netMinor'=NEW.fact->'netMinor'
    AND f->'sourceObjectId'=NEW.fact->'sourceObjectId' AND f->'sourceObjectType'=NEW.fact->'sourceObjectType' AND f->'type'=NEW.fact->'type' AND f->'balanceDomain'=NEW.fact->'balanceDomain')
  THEN RAISE EXCEPTION 'Membership money facts require the same successful provider page' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER payout_read_run_identity BEFORE INSERT OR UPDATE ON platform.payout_read_run FOR EACH ROW EXECUTE FUNCTION platform.pr14_read_provenance_guard();
CREATE TRIGGER payout_read_observation_lineage BEFORE INSERT ON platform.payout_read_observation FOR EACH ROW EXECUTE FUNCTION platform.pr14_read_provenance_guard();
CREATE TRIGGER payout_membership_lineage BEFORE INSERT ON platform.payout_membership FOR EACH ROW EXECUTE FUNCTION platform.pr14_read_provenance_guard();
REVOKE ALL ON FUNCTION platform.pr14_read_provenance_guard() FROM PUBLIC;
