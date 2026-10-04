ALTER TABLE platform.reconciliation_cursor ADD COLUMN replay_after_bt text NOT NULL DEFAULT '';
-- PR15 reuses the original task store. L3 has financial scope without a fabricated application/payment.
ALTER TABLE platform.recovery_task ALTER COLUMN operation_id DROP NOT NULL, ALTER COLUMN application_id DROP NOT NULL;
ALTER TABLE platform.recovery_task DROP CONSTRAINT recovery_task_task_type_check;
DO $$ DECLARE c record; BEGIN
 FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='platform.recovery_task'::regclass AND contype='c' AND pg_get_constraintdef(oid) LIKE '%num_nonnulls(payment_id, refund_id)%' LOOP
  EXECUTE format('ALTER TABLE platform.recovery_task DROP CONSTRAINT %I',c.conname);
 END LOOP;
END $$;
ALTER TABLE platform.recovery_task ADD COLUMN reconciliation_run_id uuid, ADD COLUMN source_bt_id uuid, ADD COLUMN livemode boolean,
 ADD CONSTRAINT recovery_task_shape CHECK(
  (task_type='OPERATION_RECOVERY' AND operation_id IS NOT NULL AND application_id IS NOT NULL AND num_nonnulls(payment_id,refund_id)=1 AND reconciliation_run_id IS NULL AND source_bt_id IS NULL AND livemode IS NULL)
  OR (task_type='RECONCILIATION_SOURCE' AND operation_id IS NULL AND application_id IS NULL AND payment_id IS NULL AND refund_id IS NULL AND reconciliation_run_id IS NOT NULL AND source_bt_id IS NOT NULL AND livemode=false)),
 ADD FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,reconciliation_run_id)
 REFERENCES platform.reconciliation_run(organization_id,environment_id,provider_account_id,livemode,id),
 ADD FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,source_bt_id)
 REFERENCES platform.balance_transaction(organization_id,environment_id,provider_account_id,livemode,id),
 ADD UNIQUE(reconciliation_run_id,source_bt_id),
 ADD UNIQUE(organization_id,environment_id,provider_account_id,livemode,id);
CREATE INDEX reconciliation_source_due ON platform.recovery_task(reconciliation_run_id,available_at,id) WHERE task_type='RECONCILIATION_SOURCE' AND status IN ('READY','WAITING','RUNNING');

CREATE TABLE platform.order_provider_binding (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, environment_id uuid NOT NULL,
 application_id uuid NOT NULL, provider_account_id uuid NOT NULL, livemode boolean NOT NULL CHECK(NOT livemode), currency text NOT NULL,
 order_id uuid NOT NULL, payment_intent_id text NOT NULL CHECK(length(btrim(payment_intent_id))>0), amount_minor bigint NOT NULL CHECK(amount_minor>0),
 proof_kind text NOT NULL CHECK(proof_kind IN ('SERVER_OPERATION','SERVER_ORDER_FIXTURE')), proof_ref text NOT NULL CHECK(length(btrim(proof_ref))>0), proof_digest text NOT NULL CHECK(proof_digest ~ '^[a-f0-9]{64}$'),
 eligible_until timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(organization_id,environment_id,application_id,provider_account_id,currency,order_id) REFERENCES platform.business_order(organization_id,environment_id,application_id,provider_account_id,currency,id),
 FOREIGN KEY(organization_id,environment_id,livemode) REFERENCES platform.environment_config(organization_id,id,livemode),
 UNIQUE(organization_id,environment_id,provider_account_id,livemode,payment_intent_id),
 UNIQUE(organization_id,environment_id,application_id,provider_account_id,currency,order_id),
 UNIQUE(organization_id,environment_id,provider_account_id,livemode,id)
);
CREATE TRIGGER immutable_order_binding BEFORE UPDATE OR DELETE ON platform.order_provider_binding FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence();

ALTER TABLE platform.balance_transaction ADD UNIQUE(organization_id,environment_id,provider_account_id,id);
ALTER TABLE platform.reconciliation_run ADD UNIQUE(organization_id,environment_id,provider_account_id,currency,id);
ALTER TABLE platform.payment ADD COLUMN origin text NOT NULL DEFAULT 'NATIVE' CHECK(origin IN ('NATIVE','RECONCILIATION')),
 ADD COLUMN reconciliation_run_id uuid, ADD COLUMN reconciliation_source_bt_id uuid, ADD COLUMN reconciliation_evidence_digest text,
 ADD CHECK((origin='NATIVE' AND reconciliation_run_id IS NULL AND reconciliation_source_bt_id IS NULL AND reconciliation_evidence_digest IS NULL)
 OR (origin='RECONCILIATION' AND reconciliation_run_id IS NOT NULL AND reconciliation_source_bt_id IS NOT NULL AND reconciliation_evidence_digest ~ '^[a-f0-9]{64}$')),
 ADD FOREIGN KEY(organization_id,environment_id,provider_account_id,reconciliation_source_bt_id)
 REFERENCES platform.balance_transaction(organization_id,environment_id,provider_account_id,id),
 ADD FOREIGN KEY(organization_id,environment_id,provider_account_id,currency,reconciliation_run_id)
 REFERENCES platform.reconciliation_run(organization_id,environment_id,provider_account_id,currency,id);
-- Existing BT key includes livemode; this narrower identity is globally unique by id.

CREATE TABLE platform.reconciliation_source_observation (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, environment_id uuid NOT NULL,
 provider_account_id uuid NOT NULL, livemode boolean NOT NULL CHECK(NOT livemode), run_id uuid NOT NULL, source_bt_id uuid NOT NULL,
 outcome text NOT NULL, reason text, fact jsonb NOT NULL CHECK(jsonb_typeof(fact)='object'), evidence jsonb NOT NULL CHECK(jsonb_typeof(evidence)='object'),
 digest text NOT NULL CHECK(digest ~ '^[a-f0-9]{64}$'), created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,run_id) REFERENCES platform.reconciliation_run(organization_id,environment_id,provider_account_id,livemode,id),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,source_bt_id) REFERENCES platform.balance_transaction(organization_id,environment_id,provider_account_id,livemode,id),
 UNIQUE(organization_id,environment_id,provider_account_id,livemode,id)
);
CREATE TABLE platform.reconciliation_payment_provenance (
 payment_id uuid PRIMARY KEY, organization_id uuid NOT NULL, environment_id uuid NOT NULL, application_id uuid NOT NULL,
 provider_account_id uuid NOT NULL, livemode boolean NOT NULL CHECK(NOT livemode), currency text NOT NULL,
 run_id uuid NOT NULL, source_bt_id uuid NOT NULL, binding_id uuid NOT NULL, observation_id uuid NOT NULL,
 charge_id text NOT NULL, payment_intent_id text NOT NULL, evidence_digest text NOT NULL CHECK(evidence_digest ~ '^[a-f0-9]{64}$'), origin text NOT NULL DEFAULT 'RECONCILIATION' CHECK(origin='RECONCILIATION'),
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(source_bt_id),
 FOREIGN KEY(organization_id,environment_id,application_id,provider_account_id,currency,payment_id) REFERENCES platform.payment(organization_id,environment_id,application_id,provider_account_id,currency,id),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,run_id) REFERENCES platform.reconciliation_run(organization_id,environment_id,provider_account_id,livemode,id),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,source_bt_id) REFERENCES platform.balance_transaction(organization_id,environment_id,provider_account_id,livemode,id),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,binding_id) REFERENCES platform.order_provider_binding(organization_id,environment_id,provider_account_id,livemode,id),
 FOREIGN KEY(organization_id,environment_id,provider_account_id,livemode,observation_id) REFERENCES platform.reconciliation_source_observation(organization_id,environment_id,provider_account_id,livemode,id)
);
CREATE TRIGGER immutable_reconciliation_source BEFORE UPDATE OR DELETE ON platform.reconciliation_source_observation FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence();
CREATE TRIGGER immutable_reconciliation_provenance BEFORE UPDATE OR DELETE ON platform.reconciliation_payment_provenance FOR EACH ROW EXECUTE FUNCTION platform.pr10_immutable_evidence();
CREATE FUNCTION platform.pr15_source_identity() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,platform AS $$
BEGIN
 IF TG_TABLE_NAME='recovery_task' THEN
  IF (NEW.reconciliation_run_id,NEW.source_bt_id,NEW.livemode) IS DISTINCT FROM (OLD.reconciliation_run_id,OLD.source_bt_id,OLD.livemode) THEN RAISE EXCEPTION 'Immutable L3 task identity' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='payment' THEN
  IF (NEW.origin,NEW.reconciliation_run_id,NEW.reconciliation_source_bt_id,NEW.reconciliation_evidence_digest) IS DISTINCT FROM (OLD.origin,OLD.reconciliation_run_id,OLD.reconciliation_source_bt_id,OLD.reconciliation_evidence_digest) THEN RAISE EXCEPTION 'Immutable L3 payment identity' USING ERRCODE='23514'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM platform.payment p JOIN platform.order_provider_binding b ON b.id=NEW.binding_id
   JOIN platform.reconciliation_source_observation o ON o.id=NEW.observation_id JOIN platform.balance_transaction bt ON bt.id=NEW.source_bt_id
   WHERE p.id=NEW.payment_id AND p.business_order_id=b.order_id AND p.origin='RECONCILIATION'
    AND p.reconciliation_run_id=NEW.run_id AND p.reconciliation_source_bt_id=bt.id AND p.reconciliation_evidence_digest=NEW.evidence_digest
    AND (b.organization_id,b.environment_id,b.application_id,b.provider_account_id,b.currency,b.amount_minor,b.payment_intent_id)
      =(p.organization_id,p.environment_id,p.application_id,p.provider_account_id,p.currency,p.amount_minor,NEW.payment_intent_id)
    AND bt.balance_domain='PAYMENTS' AND bt.business_type='charge' AND bt.source_object_type='charge' AND bt.source_object_id=NEW.charge_id AND bt.amount_minor=p.amount_minor
    AND o.run_id=NEW.run_id AND o.source_bt_id=bt.id AND o.digest=NEW.evidence_digest AND o.outcome='LINKED'
    AND o.fact->>'chargeId'=NEW.charge_id AND o.fact->>'paymentIntentId'=NEW.payment_intent_id
    AND (o.fact->>'amountMinor')::bigint=p.amount_minor AND o.fact->>'status'='succeeded')
  THEN RAISE EXCEPTION 'L3 provenance source mismatch' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION platform.pr15_source_identity() FROM PUBLIC;
CREATE TRIGGER l3_task_identity BEFORE UPDATE ON platform.recovery_task FOR EACH ROW EXECUTE FUNCTION platform.pr15_source_identity();
CREATE TRIGGER l3_payment_identity BEFORE UPDATE ON platform.payment FOR EACH ROW EXECUTE FUNCTION platform.pr15_source_identity();
CREATE TRIGGER l3_provenance_source BEFORE INSERT ON platform.reconciliation_payment_provenance FOR EACH ROW EXECUTE FUNCTION platform.pr15_source_identity();
-- A binding is derived only from the server-authored command and a confirmed local provider identity.
CREATE FUNCTION platform.pr15_native_binding() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,platform AS $$
DECLARE op platform.provider_operation%ROWTYPE;
BEGIN
 IF NEW.origin<>'NATIVE' OR NEW.payment_intent_id IS NULL OR NEW.last_provider_confirmed_at IS NULL OR NEW.resolution_status<>'KNOWN' THEN RETURN NEW; END IF;
 SELECT * INTO op FROM platform.provider_operation WHERE payment_id=NEW.id AND operation_type='CREATE_CHECKOUT';
 IF NOT FOUND OR op.command->'paymentIntentMetadata'->>'business_order_id' IS DISTINCT FROM NEW.business_order_id::text
 OR op.command->'paymentIntentMetadata'->>'payment_id' IS DISTINCT FROM NEW.id::text THEN RETURN NEW; END IF;
 INSERT INTO platform.order_provider_binding(organization_id,environment_id,application_id,provider_account_id,livemode,currency,order_id,payment_intent_id,amount_minor,proof_kind,proof_ref,proof_digest)
 VALUES(NEW.organization_id,NEW.environment_id,NEW.application_id,NEW.provider_account_id,false,NEW.currency,NEW.business_order_id,NEW.payment_intent_id,NEW.amount_minor,'SERVER_OPERATION',op.id::text,encode(sha256(convert_to(op.command::text||NEW.payment_intent_id,'UTF8')),'hex')) ON CONFLICT DO NOTHING;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION platform.pr15_native_binding() FROM PUBLIC;
CREATE TRIGGER native_provider_binding AFTER UPDATE ON platform.payment FOR EACH ROW EXECUTE FUNCTION platform.pr15_native_binding();
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='payments_app') THEN
 GRANT SELECT ON platform.order_provider_binding TO payments_app;
 GRANT SELECT,INSERT ON platform.reconciliation_source_observation,platform.reconciliation_payment_provenance TO payments_app;
END IF; END $$;

CREATE OR REPLACE FUNCTION platform.write_payment_notification(payment_id_arg uuid) RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog,platform AS $$
DECLARE p platform.payment%ROWTYPE; o platform.business_order%ROWTYPE; previous platform.outbox_event%ROWTYPE; event_uuid uuid;
 kind text; body text; business_key_value text; origin_value text; occurred timestamptz;
BEGIN
 SELECT * INTO p FROM platform.payment WHERE id=payment_id_arg FOR UPDATE;
 IF NOT FOUND OR p.resolution_status<>'KNOWN' THEN RETURN NULL; END IF;
 SELECT * INTO STRICT o FROM platform.business_order WHERE id=p.business_order_id;
 kind:=CASE WHEN p.payment_status='SUCCEEDED' AND p.captured_amount_minor=p.amount_minor THEN 'payment.succeeded'
 WHEN p.payment_status='CANCELED' THEN 'payment.canceled' ELSE 'payment.state_changed' END;
 SELECT id INTO event_uuid FROM platform.outbox_event WHERE payment_id=p.id AND event_type=kind
 AND (kind<>'payment.state_changed' OR resource_version=p.version);
 IF FOUND THEN RETURN event_uuid; END IF;
 -- A query can increment resource version without changing the confirmed business state.
 IF kind='payment.state_changed' THEN
  SELECT * INTO previous FROM platform.outbox_event WHERE payment_id=p.id
   ORDER BY occurred_at DESC,resource_version DESC LIMIT 1;
  IF FOUND AND previous.event_type=kind AND previous.payload::jsonb->>'paymentStatus'=p.payment_status
   AND (previous.payload::jsonb->>'capturedAmountMinor')::bigint=p.captured_amount_minor THEN RETURN previous.id; END IF;
 END IF;
 event_uuid:=gen_random_uuid(); occurred:=clock_timestamp();
 SELECT origin INTO origin_value FROM platform.order_fulfillment_eligibility WHERE order_id=o.id;
 origin_value:=p.origin;
 business_key_value:=p.organization_id||':'||p.environment_id||':'||p.application_id||':'||o.id||':'||kind;
 body:=jsonb_build_object('schemaVersion',1,'eventId',event_uuid,'eventType',kind,
 'organizationId',p.organization_id,'environmentId',p.environment_id,'applicationId',p.application_id,
 'providerAccountId',p.provider_account_id,'paymentId',p.id,'orderId',o.id,'orderKey',o.order_key,
 'resourceVersion',p.version,'currency',p.currency,'amountMinor',p.amount_minor,
 'capturedAmountMinor',p.captured_amount_minor,'paymentStatus',p.payment_status,'origin',origin_value,
 'occurredAt',occurred,'businessKey',business_key_value)::text;
 INSERT INTO platform.outbox_event(id,organization_id,environment_id,application_id,provider_account_id,currency,
 payment_id,order_id,event_type,resource_version,business_key,origin,occurred_at,payload,payload_digest)
 VALUES(event_uuid,p.organization_id,p.environment_id,p.application_id,p.provider_account_id,p.currency,
 p.id,o.id,kind,p.version,business_key_value,origin_value,occurred,body,encode(sha256(convert_to(body,'UTF8')),'hex'));
 RETURN event_uuid;
END $$;

-- SELECT FOR UPDATE needs UPDATE privilege. Keep the immutable table's runtime allowlist narrow.
CREATE FUNCTION platform.lock_reconciliation_order(p_order uuid,p_org uuid,p_env uuid,p_provider uuid) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,platform AS $$
BEGIN
 PERFORM 1 FROM platform.business_order WHERE id=p_order AND organization_id=p_org AND environment_id=p_env AND provider_account_id=p_provider FOR UPDATE;
 RETURN FOUND;
END $$;
REVOKE ALL ON FUNCTION platform.lock_reconciliation_order(uuid,uuid,uuid,uuid) FROM PUBLIC;
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='payments_app') THEN
 GRANT EXECUTE ON FUNCTION platform.lock_reconciliation_order(uuid,uuid,uuid,uuid) TO payments_app;
END IF; END $$;
