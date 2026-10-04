-- PR15: independent, versioned reconciliation reports. No monetary write privilege is added.
CREATE TABLE platform.reconciliation_run (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, environment_id uuid NOT NULL,
 provider_account_id uuid NOT NULL, livemode boolean NOT NULL CHECK(NOT livemode), currency text NOT NULL,
 balance_domain text NOT NULL DEFAULT 'PAYMENTS' CHECK(balance_domain='PAYMENTS'), business_date date NOT NULL, business_zone text NOT NULL,
 from_at timestamptz NOT NULL, until_at timestamptz NOT NULL, import_window_id uuid NOT NULL UNIQUE,
 rule_version text NOT NULL, state text NOT NULL DEFAULT 'INCOMPLETE' CHECK(state IN('INCOMPLETE','COMPLETE')),
 snapshot text, input_versions jsonb, ledger_cutoff timestamptz, finished_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), CHECK(from_at<until_at),
 CHECK(state<>'COMPLETE' OR finished_at IS NOT NULL),
 UNIQUE(organization_id,environment_id,provider_account_id,livemode,id),
 UNIQUE(organization_id,environment_id,provider_account_id,livemode,currency,id),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,import_window_id)
 REFERENCES platform.import_window(organization_id,environment_id,provider_account_id,livemode,id),
 FOREIGN KEY(organization_id,environment_id,currency) REFERENCES platform.org_currency_enabled(organization_id,environment_id,currency)
);
CREATE TABLE platform.reconciliation_cursor (
 run_id uuid PRIMARY KEY, organization_id uuid NOT NULL, environment_id uuid NOT NULL, provider_account_id uuid NOT NULL,
 livemode boolean NOT NULL, currency text NOT NULL, next_cursor text, api_version text, import_version bigint NOT NULL DEFAULT 0,
 accepted_pages bigint NOT NULL DEFAULT 0, updated_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,currency,run_id)
 REFERENCES platform.reconciliation_run(organization_id,environment_id,provider_account_id,livemode,currency,id)
);
CREATE TABLE platform.reconciliation_evidence (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), run_id uuid NOT NULL, organization_id uuid NOT NULL, environment_id uuid NOT NULL,
 provider_account_id uuid NOT NULL, livemode boolean NOT NULL, currency text NOT NULL,
 evidence_kind text NOT NULL, source text NOT NULL, evidence_version text NOT NULL, digest text NOT NULL,
 payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object'), created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,currency,run_id)
 REFERENCES platform.reconciliation_run(organization_id,environment_id,provider_account_id,livemode,currency,id)
);
CREATE TABLE platform.reconciliation_exception (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), run_id uuid NOT NULL, organization_id uuid NOT NULL, environment_id uuid NOT NULL,
 provider_account_id uuid NOT NULL, livemode boolean NOT NULL, currency text NOT NULL, reason text NOT NULL, owner text NOT NULL,
 source_ref text, residual numeric NOT NULL DEFAULT 0, input_versions jsonb NOT NULL, evidence jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,currency,run_id)
 REFERENCES platform.reconciliation_run(organization_id,environment_id,provider_account_id,livemode,currency,id)
);
CREATE TABLE platform.reconciliation_check (
 run_id uuid NOT NULL, check_number integer NOT NULL CHECK(check_number BETWEEN 1 AND 7), name text NOT NULL,
 organization_id uuid NOT NULL, environment_id uuid NOT NULL, provider_account_id uuid NOT NULL, livemode boolean NOT NULL, currency text NOT NULL,
 outcome text NOT NULL CHECK(outcome IN('PASS','FAIL','INCOMPLETE')), residual numeric NOT NULL, reason text NOT NULL, owner text NOT NULL,
 input_versions jsonb NOT NULL, snapshot text NOT NULL, completeness jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(run_id,check_number),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,currency,run_id)
 REFERENCES platform.reconciliation_run(organization_id,environment_id,provider_account_id,livemode,currency,id)
);
CREATE TABLE platform.fee_model (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, environment_id uuid NOT NULL, provider_account_id uuid NOT NULL,
 livemode boolean NOT NULL CHECK(NOT livemode), currency text NOT NULL, balance_domain text NOT NULL CHECK(balance_domain='PAYMENTS'), business_type text NOT NULL,
 model_version text NOT NULL CHECK(length(btrim(model_version))>0), effective_from timestamptz NOT NULL, effective_until timestamptz NOT NULL,
 basis_points integer NOT NULL CHECK(basis_points BETWEEN 0 AND 10000), flat_minor bigint NOT NULL CHECK(flat_minor>=0),
 rounding text NOT NULL CHECK(rounding IN('HALF_UP','HALF_EVEN','DOWN','UP')),
 fee_direction text NOT NULL DEFAULT 'FOLLOW_PRINCIPAL' CHECK(fee_direction IN('FOLLOW_PRINCIPAL','EXPENSE','REBATE')), created_at timestamptz NOT NULL DEFAULT now(),
 CHECK(effective_from<effective_until), UNIQUE(organization_id,environment_id,provider_account_id,livemode,currency,balance_domain,business_type,model_version),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode) REFERENCES platform.ingestion_policy(organization_id,environment_id,provider_account_id,livemode),
 FOREIGN KEY(organization_id,environment_id,currency) REFERENCES platform.org_currency_enabled(organization_id,environment_id,currency)
);
CREATE FUNCTION platform.pr15_run_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.finished_at IS NOT NULL THEN RAISE EXCEPTION 'Immutable finished reconciliation run' USING ERRCODE='23514'; END IF;
 IF (to_jsonb(NEW)-ARRAY['state','snapshot','input_versions','ledger_cutoff','finished_at']) IS DISTINCT FROM
 (to_jsonb(OLD)-ARRAY['state','snapshot','input_versions','ledger_cutoff','finished_at']) THEN
 RAISE EXCEPTION 'Immutable reconciliation scope' USING ERRCODE='23514'; END IF;
 IF NEW.finished_at IS NOT NULL AND (SELECT count(*) FROM platform.reconciliation_check WHERE run_id=NEW.id)<>7 THEN
 RAISE EXCEPTION 'Exactly seven checks required' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER reconciliation_run_guard BEFORE UPDATE OR DELETE ON platform.reconciliation_run FOR EACH ROW EXECUTE FUNCTION platform.pr15_run_guard();
CREATE FUNCTION platform.pr15_cursor_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
BEGIN
 IF TG_OP='DELETE' OR (NEW.run_id,NEW.organization_id,NEW.environment_id,NEW.provider_account_id,NEW.livemode,NEW.currency)
 IS DISTINCT FROM (OLD.run_id,OLD.organization_id,OLD.environment_id,OLD.provider_account_id,OLD.livemode,OLD.currency)
 OR EXISTS(SELECT 1 FROM platform.reconciliation_run WHERE id=OLD.run_id AND finished_at IS NOT NULL) THEN
 RAISE EXCEPTION 'Immutable reconciliation cursor identity or finished cursor' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER reconciliation_cursor_guard BEFORE UPDATE OR DELETE ON platform.reconciliation_cursor FOR EACH ROW EXECUTE FUNCTION platform.pr15_cursor_guard();
CREATE FUNCTION platform.pr15_child_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
DECLARE completed timestamptz;
BEGIN
 -- Serialize append against finish's run lock. An earlier MVCC read followed by an FK wait
 -- otherwise permits a child to commit after the report has frozen its snapshot.
 SELECT finished_at INTO completed FROM platform.reconciliation_run WHERE id=NEW.run_id FOR SHARE;
 IF completed IS NOT NULL THEN
 RAISE EXCEPTION 'Finished reconciliation evidence is immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['reconciliation_evidence','reconciliation_exception','reconciliation_check'] LOOP
  EXECUTE format('CREATE TRIGGER immutable_reconciliation BEFORE UPDATE OR DELETE ON platform.%I FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence()',t);
  EXECUTE format('CREATE TRIGGER reconciliation_child_guard BEFORE INSERT ON platform.%I FOR EACH ROW EXECUTE FUNCTION platform.pr15_child_guard()',t);
 END LOOP;
END $$;
CREATE TRIGGER immutable_fee_model BEFORE UPDATE OR DELETE ON platform.fee_model FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence();
REVOKE ALL ON FUNCTION platform.pr15_run_guard(),platform.pr15_cursor_guard(),platform.pr15_child_guard() FROM PUBLIC;
DO $$ DECLARE t text; BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='payments_app') THEN
  FOREACH t IN ARRAY ARRAY['reconciliation_run','reconciliation_cursor','reconciliation_evidence','reconciliation_exception','reconciliation_check','fee_model'] LOOP
   EXECUTE format('REVOKE ALL ON platform.%I FROM payments_app',t);
   EXECUTE format('GRANT SELECT ON platform.%I TO payments_app',t);
  END LOOP;
  GRANT INSERT ON platform.reconciliation_run,platform.reconciliation_cursor,platform.reconciliation_evidence,platform.reconciliation_exception,platform.reconciliation_check TO payments_app;
  GRANT UPDATE ON platform.reconciliation_run,platform.reconciliation_cursor TO payments_app;
  REVOKE ALL ON FUNCTION platform.pr15_run_guard(),platform.pr15_cursor_guard(),platform.pr15_child_guard() FROM payments_app;
 END IF;
END $$;
