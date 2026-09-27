-- No backfill: old business orders require explicit eligibility review.
CREATE TABLE platform.order_fulfillment_eligibility (
 organization_id uuid NOT NULL, environment_id uuid NOT NULL, application_id uuid NOT NULL,
 provider_account_id uuid NOT NULL, currency text NOT NULL, order_id uuid NOT NULL,
 amount_minor bigint NOT NULL CHECK(amount_minor>0), origin text NOT NULL CHECK(origin IN ('NATIVE','RECONCILIATION')),
 eligible boolean NOT NULL, policy_version integer NOT NULL CHECK(policy_version>0),
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(order_id),
 FOREIGN KEY(organization_id,environment_id,application_id,provider_account_id,currency,order_id)
 REFERENCES platform.business_order(organization_id,environment_id,application_id,provider_account_id,currency,id)
);
CREATE FUNCTION platform.pr09_native_order_policy() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog,platform AS $$ BEGIN
 INSERT INTO platform.order_fulfillment_eligibility
 (organization_id,environment_id,application_id,provider_account_id,currency,order_id,amount_minor,origin,eligible,policy_version)
 VALUES(NEW.organization_id,NEW.environment_id,NEW.application_id,NEW.provider_account_id,NEW.currency,NEW.id,NEW.amount_minor,'NATIVE',true,1);
 RETURN NEW;
END $$;
CREATE TRIGGER native_order_policy AFTER INSERT ON platform.business_order
 FOR EACH ROW EXECUTE FUNCTION platform.pr09_native_order_policy();
-- Trigger execution uses the migration-owned binding. Runtime must not mount this
-- definer function on a temporary table to mint eligibility for legacy orders.
REVOKE ALL ON FUNCTION platform.pr09_native_order_policy() FROM PUBLIC;

CREATE TABLE platform.outbox_event (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, environment_id uuid NOT NULL, application_id uuid NOT NULL,
 provider_account_id uuid NOT NULL, currency text NOT NULL, payment_id uuid NOT NULL, order_id uuid NOT NULL,
 event_type text NOT NULL CHECK(event_type IN ('payment.succeeded','payment.canceled','payment.state_changed')),
 resource_version bigint NOT NULL CHECK(resource_version>=0), business_key text NOT NULL,
 origin text NOT NULL CHECK(origin IN ('NATIVE','RECONCILIATION')), occurred_at timestamptz NOT NULL,
 payload text NOT NULL CHECK(octet_length(payload)<=16384 AND jsonb_typeof(payload::jsonb)='object'),
 payload_digest text NOT NULL CHECK(payload_digest=encode(sha256(convert_to(payload,'UTF8')),'hex')),
 status text NOT NULL DEFAULT 'READY' CHECK(status IN ('READY','RUNNING','PUBLISHED')),
 available_at timestamptz NOT NULL DEFAULT now(), lease_token uuid, lease_until timestamptz,
 attempt_count integer NOT NULL DEFAULT 0 CHECK(attempt_count>=0), last_reason text, published_at timestamptz,
 UNIQUE(organization_id,environment_id,application_id,id),
 UNIQUE(organization_id,environment_id,application_id,payment_id,event_type,resource_version),
 FOREIGN KEY(organization_id,environment_id,application_id,provider_account_id,currency,payment_id)
 REFERENCES platform.payment(organization_id,environment_id,application_id,provider_account_id,currency,id),
 FOREIGN KEY(organization_id,environment_id,application_id,provider_account_id,currency,order_id)
 REFERENCES platform.business_order(organization_id,environment_id,application_id,provider_account_id,currency,id)
);
CREATE UNIQUE INDEX outbox_terminal_identity ON platform.outbox_event(organization_id,environment_id,application_id,payment_id,event_type)
 WHERE event_type IN ('payment.succeeded','payment.canceled');
CREATE INDEX outbox_due ON platform.outbox_event(available_at,id) WHERE status<>'PUBLISHED';

-- The caller holds payment rank 20 before inserting outbox rank 90. All confirmation paths use this writer.
CREATE FUNCTION platform.write_payment_notification(payment_id_arg uuid) RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER
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
 origin_value:=coalesce(origin_value,'NATIVE');
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
-- Only the canonical writer may create authoritative event bytes; runtime can
-- execute it with a payment id but cannot supply arbitrary scope or payload.
REVOKE ALL ON FUNCTION platform.write_payment_notification(uuid) FROM PUBLIC;
CREATE FUNCTION platform.pr09_payment_notification() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='UPDATE' AND (NEW.payment_status,NEW.resolution_status,NEW.captured_amount_minor)
 IS NOT DISTINCT FROM (OLD.payment_status,OLD.resolution_status,OLD.captured_amount_minor) THEN RETURN NEW; END IF;
 PERFORM platform.write_payment_notification(NEW.id); RETURN NEW;
END $$;
CREATE TRIGGER payment_notification AFTER INSERT OR UPDATE ON platform.payment
 FOR EACH ROW EXECUTE FUNCTION platform.pr09_payment_notification();

CREATE TABLE platform.merchant_notification_target (
 organization_id uuid NOT NULL, environment_id uuid NOT NULL, application_id uuid NOT NULL,
 url text NOT NULL CHECK(length(url)<=2048), key_ref text NOT NULL CHECK(key_ref ~ '^[A-Za-z][A-Za-z0-9_-]{0,63}$'),
 version bigint NOT NULL CHECK(version>0), enabled boolean NOT NULL DEFAULT true,
 PRIMARY KEY(organization_id,environment_id,application_id),
 FOREIGN KEY(organization_id,environment_id,application_id) REFERENCES platform.application(organization_id,environment_id,id)
);
CREATE TABLE platform.consumer_inbox (
 consumer_name text NOT NULL, event_id uuid NOT NULL, organization_id uuid NOT NULL,
 environment_id uuid NOT NULL, application_id uuid NOT NULL,
 payload_digest text NOT NULL, received_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(consumer_name,event_id),
 FOREIGN KEY(organization_id,environment_id,application_id,event_id)
 REFERENCES platform.outbox_event(organization_id,environment_id,application_id,id)
);
CREATE TABLE platform.delivery_task (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), event_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL, environment_id uuid NOT NULL, application_id uuid NOT NULL,
 status text NOT NULL DEFAULT 'READY' CHECK(status IN ('READY','RUNNING','HELD','DELIVERED','DEAD')),
 target_url text, target_key_ref text, target_version bigint,
 available_at timestamptz NOT NULL DEFAULT now(), lease_token uuid, lease_until timestamptz,
 attempt_count integer NOT NULL DEFAULT 0 CHECK(attempt_count>=0),
 last_reason text, last_status integer, created_at timestamptz NOT NULL DEFAULT now(),
 first_attempt_at timestamptz, last_attempt_at timestamptz, completed_at timestamptz,
 CHECK(num_nonnulls(target_url,target_key_ref,target_version) IN (0,3)),
 FOREIGN KEY(organization_id,environment_id,application_id,event_id)
 REFERENCES platform.outbox_event(organization_id,environment_id,application_id,id)
);
CREATE INDEX delivery_due ON platform.delivery_task(available_at,id) WHERE status IN ('READY','RUNNING','HELD');
CREATE TABLE platform.notification_rejection (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), consumer_name text NOT NULL,
 payload_digest text NOT NULL, reason text NOT NULL CHECK(reason IN ('MALFORMED','UNKNOWN_EVENT','PAYLOAD_CONFLICT')),
 received_at timestamptz NOT NULL DEFAULT now(), UNIQUE(consumer_name,payload_digest,reason)
);
CREATE FUNCTION platform.pr09_evidence_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Immutable notification evidence' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='outbox_event' THEN
  IF (to_jsonb(NEW)-ARRAY['status','available_at','lease_token','lease_until','attempt_count','last_reason','published_at'])
  IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','available_at','lease_token','lease_until','attempt_count','last_reason','published_at']) THEN RETURN NEW; END IF;
 ELSIF TG_TABLE_NAME='delivery_task' THEN
  IF (NEW.id,NEW.event_id,NEW.organization_id,NEW.environment_id,NEW.application_id,NEW.created_at)
  IS NOT DISTINCT FROM (OLD.id,OLD.event_id,OLD.organization_id,OLD.environment_id,OLD.application_id,OLD.created_at)
  AND (OLD.target_version IS NULL OR (NEW.target_url,NEW.target_key_ref,NEW.target_version)
  IS NOT DISTINCT FROM (OLD.target_url,OLD.target_key_ref,OLD.target_version)) THEN RETURN NEW; END IF;
 END IF;
 RAISE EXCEPTION 'Immutable notification evidence' USING ERRCODE='23514';
END $$;
CREATE TRIGGER outbox_evidence BEFORE UPDATE OR DELETE ON platform.outbox_event FOR EACH ROW EXECUTE FUNCTION platform.pr09_evidence_guard();
CREATE TRIGGER inbox_evidence BEFORE UPDATE OR DELETE ON platform.consumer_inbox FOR EACH ROW EXECUTE FUNCTION platform.pr09_evidence_guard();
CREATE TRIGGER rejection_evidence BEFORE UPDATE OR DELETE ON platform.notification_rejection FOR EACH ROW EXECUTE FUNCTION platform.pr09_evidence_guard();
CREATE TRIGGER delivery_identity BEFORE UPDATE OR DELETE ON platform.delivery_task FOR EACH ROW EXECUTE FUNCTION platform.pr09_evidence_guard();
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='payments_app') THEN
  REVOKE ALL ON FUNCTION platform.pr09_native_order_policy() FROM payments_app;
  GRANT EXECUTE ON FUNCTION platform.write_payment_notification(uuid) TO payments_app;
  -- Production migrator defaults grant SELECT + INSERT; reset every new table
  -- before granting the exact runtime surface, including read-only policy/config.
  REVOKE ALL ON platform.outbox_event,platform.delivery_task,platform.consumer_inbox,
   platform.notification_rejection,platform.order_fulfillment_eligibility,platform.merchant_notification_target FROM payments_app;
  GRANT SELECT ON platform.order_fulfillment_eligibility,platform.merchant_notification_target TO payments_app;
  GRANT SELECT,UPDATE ON platform.outbox_event TO payments_app;
  GRANT SELECT,INSERT,UPDATE ON platform.delivery_task TO payments_app;
  GRANT SELECT,INSERT ON platform.consumer_inbox,platform.notification_rejection TO payments_app;
 END IF;
END $$;
