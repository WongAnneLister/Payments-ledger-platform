-- PR11: one payment control row; all money writes keep the existing operation and recovery identities.
CREATE TABLE platform.refund_control (
 payment_id uuid PRIMARY KEY, organization_id uuid NOT NULL, environment_id uuid NOT NULL,
 application_id uuid NOT NULL, provider_account_id uuid NOT NULL, currency text NOT NULL,
 captured_minor bigint NOT NULL CHECK(captured_minor>=0), refunded_minor bigint NOT NULL DEFAULT 0 CHECK(refunded_minor>=0),
 reserved_minor bigint NOT NULL DEFAULT 0 CHECK(reserved_minor>=0),
 control_state text NOT NULL DEFAULT 'NORMAL' CHECK(control_state IN ('NORMAL','EXTERNAL_CONFLICT','DISPUTE_BLOCKED')),
 version bigint NOT NULL DEFAULT 0, updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(control_state<>'NORMAL' OR refunded_minor::numeric+reserved_minor::numeric<=captured_minor),
 FOREIGN KEY(organization_id,environment_id,application_id,provider_account_id,currency,payment_id)
 REFERENCES platform.payment(organization_id,environment_id,application_id,provider_account_id,currency,id)
);
CREATE TABLE platform.refund (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, environment_id uuid NOT NULL, application_id uuid NOT NULL,
 provider_account_id uuid NOT NULL, currency text NOT NULL, payment_id uuid NOT NULL,
 amount_minor bigint NOT NULL CHECK(amount_minor>0), client_refund_key text, request_fingerprint text NOT NULL,
 source text NOT NULL CHECK(source IN ('LOCAL','EXTERNAL')), reason text, note text,
 refund_status text NOT NULL DEFAULT 'RESERVED' CHECK(refund_status IN ('RESERVED','SUBMITTED','PENDING','REQUIRES_ACTION','SUCCEEDED','FAILED','CANCELED')),
 resolution_status text NOT NULL DEFAULT 'KNOWN' CHECK(resolution_status IN ('KNOWN','UNKNOWN','CONFLICT')),
 provider_status text, provider_refund_id text, provider_charge_id text NOT NULL,
 balance_transaction_id text, failure_balance_transaction_id text, failure_reason text,
 last_provider_confirmed_at timestamptz, unresolved_since timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), version bigint NOT NULL DEFAULT 0,
 FOREIGN KEY(organization_id,environment_id,application_id,provider_account_id,currency,payment_id)
 REFERENCES platform.payment(organization_id,environment_id,application_id,provider_account_id,currency,id),
 UNIQUE(organization_id,environment_id,application_id,provider_account_id,currency,id),
 UNIQUE(organization_id,environment_id,provider_account_id,currency,id),
 UNIQUE(organization_id,environment_id,application_id,provider_account_id,currency,payment_id,id),
 UNIQUE(organization_id,environment_id,application_id,client_refund_key),
 UNIQUE(organization_id,environment_id,provider_account_id,provider_refund_id)
);
CREATE TABLE platform.refund_source_observation (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, environment_id uuid NOT NULL,
 provider_account_id uuid NOT NULL, provider_refund_id text NOT NULL, refund_id uuid, payment_id uuid,
 fact jsonb NOT NULL CHECK(jsonb_typeof(fact)='object'), evidence jsonb NOT NULL CHECK(jsonb_typeof(evidence)='object'),
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(organization_id,environment_id,provider_account_id) REFERENCES platform.provider_account(organization_id,environment_id,id),
 FOREIGN KEY(refund_id) REFERENCES platform.refund(id), FOREIGN KEY(payment_id) REFERENCES platform.payment(id)
);
CREATE INDEX refund_source_anchor ON platform.refund_source_observation(organization_id,environment_id,provider_account_id,provider_refund_id);
-- Remove only the PR06 payment-only guard; retain exclusive target association checks.
DO $$ DECLARE c record; BEGIN
 FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='platform.provider_operation'::regclass AND contype='c'
 AND pg_get_constraintdef(oid) LIKE '%operation_target = ''PAYMENT''%refund_id IS NULL%' LOOP
 EXECUTE format('ALTER TABLE platform.provider_operation DROP CONSTRAINT %I',c.conname); END LOOP;
END $$;
ALTER TABLE platform.provider_operation DROP CONSTRAINT provider_operation_operation_type_check;
ALTER TABLE platform.provider_operation ADD CHECK(operation_type IN ('CREATE_CHECKOUT','CANCEL_PAYMENT','CREATE_REFUND','QUERY_REFUND')),
 ADD CHECK((operation_target='REFUND')=(operation_type IN ('CREATE_REFUND','QUERY_REFUND'))),
 ADD FOREIGN KEY(organization_id,environment_id,application_id,provider_account_id,currency,refund_id)
 REFERENCES platform.refund(organization_id,environment_id,application_id,provider_account_id,currency,id),
 ADD UNIQUE(organization_id,environment_id,application_id,provider_account_id,currency,refund_id,id),
 ADD UNIQUE(refund_id,operation_type);
ALTER TABLE platform.recovery_task ALTER COLUMN payment_id DROP NOT NULL;
ALTER TABLE platform.recovery_task ADD COLUMN refund_id uuid,
 ADD CHECK(num_nonnulls(payment_id,refund_id)=1),
 ADD FOREIGN KEY(organization_id,environment_id,application_id,provider_account_id,currency,refund_id,operation_id)
 REFERENCES platform.provider_operation(organization_id,environment_id,application_id,provider_account_id,currency,refund_id,id);
ALTER TABLE platform.api_idempotency_record ALTER COLUMN payment_id DROP NOT NULL;
ALTER TABLE platform.api_idempotency_record DROP CONSTRAINT api_idempotency_record_request_type_check;
ALTER TABLE platform.api_idempotency_record ADD COLUMN refund_id uuid,
 ADD CHECK(request_type IN ('CREATE_PAYMENT','CANCEL_PAYMENT','CREATE_REFUND')),
 ADD CHECK(num_nonnulls(payment_id,refund_id)=1), ADD CHECK((request_type='CREATE_REFUND')=(refund_id IS NOT NULL)),
 ADD FOREIGN KEY(organization_id,environment_id,application_id,provider_account_id,currency,refund_id,operation_id)
 REFERENCES platform.provider_operation(organization_id,environment_id,application_id,provider_account_id,currency,refund_id,id);
ALTER TABLE platform.provider_observation ADD COLUMN refund_id uuid;
ALTER TABLE platform.provider_observation DROP CONSTRAINT provider_observation_observation_domain_check;
ALTER TABLE platform.provider_observation DROP CONSTRAINT observation_domain_scope;
ALTER TABLE platform.provider_observation ADD CHECK(observation_domain IN ('PAYMENT','REFUND','FINANCIAL')),
 ADD CONSTRAINT observation_domain_scope CHECK(
 (observation_domain='PAYMENT' AND application_id IS NOT NULL AND currency IS NOT NULL AND operation_id IS NOT NULL AND payment_id IS NOT NULL AND refund_id IS NULL AND import_window_id IS NULL AND livemode IS NULL)
 OR (observation_domain='REFUND' AND application_id IS NOT NULL AND currency IS NOT NULL AND operation_id IS NOT NULL AND payment_id IS NULL AND refund_id IS NOT NULL AND import_window_id IS NULL AND livemode IS NULL)
 OR (observation_domain='FINANCIAL' AND application_id IS NULL AND operation_id IS NULL AND payment_id IS NULL AND refund_id IS NULL AND livemode IS NOT NULL AND fetched_at IS NOT NULL)),
 ADD FOREIGN KEY(organization_id,environment_id,application_id,provider_account_id,currency,refund_id,operation_id)
 REFERENCES platform.provider_operation(organization_id,environment_id,application_id,provider_account_id,currency,refund_id,id);
ALTER TABLE platform.balance_transaction ADD COLUMN refund_id uuid,
 ADD FOREIGN KEY(organization_id,environment_id,provider_account_id,currency,refund_id)
 REFERENCES platform.refund(organization_id,environment_id,provider_account_id,currency,id);
CREATE FUNCTION platform.pr11_refund_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Immutable refund evidence/identity' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='refund_source_observation' THEN
  IF TG_OP='UPDATE' THEN RAISE EXCEPTION 'Immutable refund observation' USING ERRCODE='23514'; END IF;
  IF NEW.payment_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM platform.payment p WHERE p.id=NEW.payment_id AND p.organization_id=NEW.organization_id AND p.environment_id=NEW.environment_id AND p.provider_account_id=NEW.provider_account_id) THEN RAISE EXCEPTION 'Refund source payment scope mismatch' USING ERRCODE='23514'; END IF;
  IF NEW.refund_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM platform.refund r WHERE r.id=NEW.refund_id AND r.organization_id=NEW.organization_id AND r.environment_id=NEW.environment_id AND r.provider_account_id=NEW.provider_account_id AND r.payment_id=NEW.payment_id) THEN RAISE EXCEPTION 'Refund observation scope mismatch' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='refund_control_audit' THEN
  IF NOT EXISTS(SELECT 1 FROM platform.payment p JOIN platform.refund_source_observation o ON o.payment_id=p.id WHERE p.id=NEW.payment_id AND p.organization_id=NEW.organization_id AND p.environment_id=NEW.environment_id AND p.provider_account_id=NEW.provider_account_id AND o.id=NEW.observation_id) THEN RAISE EXCEPTION 'Refund audit payment scope mismatch' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='refund' THEN
  IF (NEW.id,NEW.organization_id,NEW.environment_id,NEW.application_id,NEW.provider_account_id,NEW.currency,NEW.payment_id,NEW.amount_minor,NEW.client_refund_key,NEW.request_fingerprint,NEW.source,NEW.reason,NEW.note,NEW.provider_charge_id)
   IS DISTINCT FROM (OLD.id,OLD.organization_id,OLD.environment_id,OLD.application_id,OLD.provider_account_id,OLD.currency,OLD.payment_id,OLD.amount_minor,OLD.client_refund_key,OLD.request_fingerprint,OLD.source,OLD.reason,OLD.note,OLD.provider_charge_id)
   OR OLD.provider_refund_id IS NOT NULL AND NEW.provider_refund_id IS DISTINCT FROM OLD.provider_refund_id
   OR OLD.balance_transaction_id IS NOT NULL AND NEW.balance_transaction_id IS DISTINCT FROM OLD.balance_transaction_id
   OR OLD.failure_balance_transaction_id IS NOT NULL AND NEW.failure_balance_transaction_id IS DISTINCT FROM OLD.failure_balance_transaction_id
   OR OLD.refund_status IN ('SUCCEEDED','FAILED','CANCELED') AND NEW.refund_status<>OLD.refund_status
   THEN RAISE EXCEPTION 'Immutable refund identity or terminal fact' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='refund_control' THEN
  IF (NEW.payment_id,NEW.organization_id,NEW.environment_id,NEW.application_id,NEW.provider_account_id,NEW.currency) IS DISTINCT FROM (OLD.payment_id,OLD.organization_id,OLD.environment_id,OLD.application_id,OLD.provider_account_id,OLD.currency) THEN RAISE EXCEPTION 'Immutable control scope' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='recovery_task' THEN
  IF NEW.refund_id IS DISTINCT FROM OLD.refund_id THEN RAISE EXCEPTION 'Immutable recovery refund' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='balance_transaction' THEN
  IF (OLD.refund_id IS NOT NULL OR OLD.journal_id IS NOT NULL) AND NEW.refund_id IS DISTINCT FROM OLD.refund_id THEN RAISE EXCEPTION 'Immutable refund BT association' USING ERRCODE='23514'; END IF;
  IF NEW.refund_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM platform.refund WHERE id=NEW.refund_id AND payment_id=NEW.payment_id) THEN RAISE EXCEPTION 'Refund BT payment mismatch' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER refund_identity BEFORE UPDATE OR DELETE ON platform.refund FOR EACH ROW EXECUTE FUNCTION platform.pr11_refund_guard();
CREATE TRIGGER refund_control_identity BEFORE UPDATE OR DELETE ON platform.refund_control FOR EACH ROW EXECUTE FUNCTION platform.pr11_refund_guard();
CREATE TRIGGER refund_source_immutable BEFORE INSERT OR UPDATE OR DELETE ON platform.refund_source_observation FOR EACH ROW EXECUTE FUNCTION platform.pr11_refund_guard();
CREATE TRIGGER refund_recovery_identity BEFORE UPDATE ON platform.recovery_task FOR EACH ROW EXECUTE FUNCTION platform.pr11_refund_guard();
CREATE TRIGGER refund_bt_identity BEFORE UPDATE ON platform.balance_transaction FOR EACH ROW EXECUTE FUNCTION platform.pr11_refund_guard();
ALTER TABLE platform.outbox_event ADD COLUMN refund_id uuid,
 ADD FOREIGN KEY(organization_id,environment_id,application_id,provider_account_id,currency,payment_id,refund_id)
 REFERENCES platform.refund(organization_id,environment_id,application_id,provider_account_id,currency,payment_id,id);
ALTER TABLE platform.outbox_event DROP CONSTRAINT outbox_event_event_type_check;
ALTER TABLE platform.outbox_event ADD CHECK(event_type IN ('payment.succeeded','payment.canceled','payment.state_changed','refund.succeeded','refund.failed','refund.canceled')),
 ADD CHECK((event_type LIKE 'refund.%')=(refund_id IS NOT NULL));
DO $$ DECLARE c record; BEGIN
 FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='platform.outbox_event'::regclass AND contype='u' AND pg_get_constraintdef(oid) LIKE '%payment_id, event_type, resource_version%' LOOP
 EXECUTE format('ALTER TABLE platform.outbox_event DROP CONSTRAINT %I',c.conname); END LOOP;
END $$;
CREATE UNIQUE INDEX outbox_payment_version ON platform.outbox_event(organization_id,environment_id,application_id,payment_id,event_type,resource_version) WHERE refund_id IS NULL;
CREATE UNIQUE INDEX outbox_refund_terminal ON platform.outbox_event(refund_id,event_type) WHERE refund_id IS NOT NULL;
CREATE FUNCTION platform.write_refund_notification(refund_id_arg uuid) RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,platform AS $$
DECLARE r platform.refund%ROWTYPE; p platform.payment%ROWTYPE; o platform.business_order%ROWTYPE; event_uuid uuid; kind text; body text; key_value text; occurred timestamptz; origin_value text;
BEGIN
 SELECT * INTO r FROM platform.refund WHERE id=refund_id_arg;
 IF NOT FOUND OR r.resolution_status<>'KNOWN' OR r.refund_status NOT IN ('SUCCEEDED','FAILED','CANCELED') THEN RETURN NULL; END IF;
 SELECT * INTO STRICT p FROM platform.payment WHERE id=r.payment_id;
 SELECT * INTO STRICT o FROM platform.business_order WHERE id=p.business_order_id;
 kind:='refund.'||lower(r.refund_status);
 SELECT id INTO event_uuid FROM platform.outbox_event WHERE refund_id=r.id AND event_type=kind;
 IF FOUND THEN RETURN event_uuid; END IF;
 event_uuid:=gen_random_uuid(); occurred:=clock_timestamp(); origin_value:=CASE WHEN r.source='LOCAL' THEN 'NATIVE' ELSE 'RECONCILIATION' END;
 key_value:=r.organization_id||':'||r.environment_id||':'||r.application_id||':'||r.id||':'||kind;
 body:=jsonb_build_object('schemaVersion',1,'eventId',event_uuid,'eventType',kind,'organizationId',r.organization_id,'environmentId',r.environment_id,
 'applicationId',r.application_id,'providerAccountId',r.provider_account_id,'paymentId',p.id,'orderId',o.id,'orderKey',o.order_key,
 'refundId',r.id,'refundStatus',r.refund_status,'resourceVersion',r.version,'currency',r.currency,'amountMinor',r.amount_minor,
 'origin',origin_value,'occurredAt',occurred,'businessKey',key_value)::text;
 INSERT INTO platform.outbox_event(id,organization_id,environment_id,application_id,provider_account_id,currency,payment_id,order_id,refund_id,event_type,resource_version,business_key,origin,occurred_at,payload,payload_digest)
 VALUES(event_uuid,r.organization_id,r.environment_id,r.application_id,r.provider_account_id,r.currency,p.id,o.id,r.id,kind,r.version,key_value,origin_value,occurred,body,encode(sha256(convert_to(body,'UTF8')),'hex')) ON CONFLICT DO NOTHING;
 RETURN event_uuid;
END $$;
REVOKE ALL ON FUNCTION platform.write_refund_notification(uuid) FROM PUBLIC;
CREATE FUNCTION platform.pr11_refund_notification() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 PERFORM platform.write_refund_notification(NEW.id); RETURN NEW; END $$;
CREATE TRIGGER refund_notification AFTER INSERT OR UPDATE ON platform.refund FOR EACH ROW EXECUTE FUNCTION platform.pr11_refund_notification();
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='payments_app') THEN
 REVOKE ALL ON platform.refund,platform.refund_control,platform.refund_source_observation FROM payments_app;
 GRANT SELECT,INSERT,UPDATE ON platform.refund,platform.refund_control TO payments_app;
 GRANT SELECT,INSERT ON platform.refund_source_observation TO payments_app;
 GRANT EXECUTE ON FUNCTION platform.write_refund_notification(uuid) TO payments_app;
END IF; END $$;
-- Operator recovery can only clear a fully reconciled control, with an immutable scoped evidence reference.
ALTER TABLE platform.refund_source_observation ADD UNIQUE(organization_id,environment_id,provider_account_id,id);
CREATE TABLE platform.refund_control_audit (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, environment_id uuid NOT NULL,
 provider_account_id uuid NOT NULL, payment_id uuid NOT NULL REFERENCES platform.refund_control(payment_id),
 observation_id uuid NOT NULL, audit_key text NOT NULL CHECK(length(btrim(audit_key)) BETWEEN 1 AND 255),
 before_state jsonb NOT NULL, after_state jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(payment_id,audit_key),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,observation_id)
 REFERENCES platform.refund_source_observation(organization_id,environment_id,provider_account_id,id)
);
CREATE TRIGGER refund_control_audit_scope BEFORE INSERT ON platform.refund_control_audit FOR EACH ROW EXECUTE FUNCTION platform.pr11_refund_guard();
CREATE TRIGGER refund_control_audit_immutable BEFORE UPDATE OR DELETE ON platform.refund_control_audit FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence();
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='payments_app') THEN
 REVOKE ALL ON platform.refund_control_audit FROM payments_app;
 GRANT SELECT,INSERT ON platform.refund_control_audit TO payments_app;
END IF; END $$;
