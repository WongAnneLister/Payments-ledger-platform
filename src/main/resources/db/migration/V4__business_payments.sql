-- PR06: durable business intents, scoped associations and immutable evidence. No posting or refund business.
CREATE TABLE platform.business_order (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL, environment_id uuid NOT NULL, application_id uuid NOT NULL,
    provider_account_id uuid NOT NULL, currency text NOT NULL,
order_key text NOT NULL, request_fingerprint text NOT NULL,
    product_ref text NOT NULL, price_version integer NOT NULL CHECK(price_version>0),
    quantity integer NOT NULL CHECK(quantity BETWEEN 1 AND 10000), unit_amount_minor bigint NOT NULL CHECK(unit_amount_minor>0),
    amount_minor bigint NOT NULL CHECK(amount_minor>0),
    price_snapshot jsonb NOT NULL CHECK(jsonb_typeof(price_snapshot)='object'),
    share_policy_snapshot jsonb NOT NULL CHECK(jsonb_typeof(share_policy_snapshot)='object'),
    CHECK(amount_minor::numeric=unit_amount_minor::numeric*quantity),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version>=0),
    FOREIGN KEY (organization_id,environment_id,application_id) REFERENCES platform.application(organization_id,environment_id,id),
    FOREIGN KEY (organization_id,environment_id,provider_account_id) REFERENCES platform.provider_account(organization_id,environment_id,id),
    FOREIGN KEY (organization_id,environment_id,currency) REFERENCES platform.org_currency_enabled(organization_id,environment_id,currency),
    UNIQUE (organization_id,environment_id,application_id,provider_account_id,currency,id),
    UNIQUE (organization_id,environment_id,application_id,order_key)
);
CREATE TABLE platform.payment (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL, environment_id uuid NOT NULL, application_id uuid NOT NULL,
    provider_account_id uuid NOT NULL, currency text NOT NULL,
business_order_id uuid NOT NULL, amount_minor bigint NOT NULL CHECK(amount_minor>0),
    payment_status text NOT NULL DEFAULT 'CREATED' CHECK(payment_status IN ('CREATED','AWAITING_CUSTOMER','REQUIRES_ACTION','PROCESSING','SUCCEEDED','CANCELED')),
    provider_status text,
    resolution_status text NOT NULL DEFAULT 'KNOWN' CHECK(resolution_status IN ('KNOWN','UNKNOWN','CONFLICT')),
    financial_status text NOT NULL DEFAULT 'NOT_APPLICABLE' CHECK(financial_status IN ('NOT_APPLICABLE','PENDING_EVIDENCE','PARTIALLY_POSTED','POSTED','CONFLICT')),
    captured_amount_minor bigint NOT NULL DEFAULT 0 CHECK(captured_amount_minor>=0),
    checkout_session_id text, payment_intent_id text, latest_charge_id text, hosted_url text,
    expires_at timestamptz NOT NULL, last_provider_confirmed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version>=0),
    FOREIGN KEY (organization_id,environment_id,application_id) REFERENCES platform.application(organization_id,environment_id,id),
    FOREIGN KEY (organization_id,environment_id,provider_account_id) REFERENCES platform.provider_account(organization_id,environment_id,id),
    FOREIGN KEY (organization_id,environment_id,currency) REFERENCES platform.org_currency_enabled(organization_id,environment_id,currency),
    UNIQUE (organization_id,environment_id,application_id,provider_account_id,currency,id),
    FOREIGN KEY (organization_id,environment_id,application_id,provider_account_id,currency,business_order_id) REFERENCES platform.business_order(organization_id,environment_id,application_id,provider_account_id,currency,id),
    UNIQUE (organization_id,environment_id,application_id,provider_account_id,currency,business_order_id),
    UNIQUE (organization_id,environment_id,provider_account_id,checkout_session_id)
);
CREATE TABLE platform.provider_operation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL, environment_id uuid NOT NULL, application_id uuid NOT NULL,
    provider_account_id uuid NOT NULL, currency text NOT NULL,
operation_target text NOT NULL CHECK(operation_target IN ('PAYMENT','REFUND')),
    payment_id uuid, refund_id uuid,
    operation_type text NOT NULL CHECK(operation_type IN ('CREATE_CHECKOUT','CANCEL_PAYMENT')),
    operation_key text NOT NULL UNIQUE, request_fingerprint text NOT NULL,
    command jsonb NOT NULL CHECK(jsonb_typeof(command)='object'), expires_at timestamptz NOT NULL,
    status text NOT NULL DEFAULT 'PREPARED' CHECK(status IN ('PREPARED','SENDING','ACKNOWLEDGED','UNKNOWN','RESOLVED','ESCALATED')),
    resolution text NOT NULL DEFAULT 'KNOWN' CHECK(resolution IN ('KNOWN','UNKNOWN','CONFLICT')),
    lease_owner text, lease_until timestamptz, lease_token uuid, attempt_count integer NOT NULL DEFAULT 0,
    last_query_at timestamptz, last_provider_status text, external_object_id text,
    CHECK(num_nonnulls(payment_id,refund_id)=1),
    CHECK((operation_target='PAYMENT' AND payment_id IS NOT NULL) OR (operation_target='REFUND' AND refund_id IS NOT NULL)),
    -- PR11 removes this guard and installs a scoped refund FK in the same migration.
    CHECK(operation_target='PAYMENT' AND refund_id IS NULL),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version>=0),
    FOREIGN KEY (organization_id,environment_id,application_id) REFERENCES platform.application(organization_id,environment_id,id),
    FOREIGN KEY (organization_id,environment_id,provider_account_id) REFERENCES platform.provider_account(organization_id,environment_id,id),
    FOREIGN KEY (organization_id,environment_id,currency) REFERENCES platform.org_currency_enabled(organization_id,environment_id,currency),
    UNIQUE (organization_id,environment_id,application_id,provider_account_id,currency,id),
    FOREIGN KEY (organization_id,environment_id,application_id,provider_account_id,currency,payment_id) REFERENCES platform.payment(organization_id,environment_id,application_id,provider_account_id,currency,id),
    UNIQUE (organization_id,environment_id,application_id,provider_account_id,currency,payment_id,id),
    UNIQUE (organization_id,environment_id,application_id,provider_account_id,currency,payment_id,operation_type)
);
CREATE TABLE platform.api_idempotency_record (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL, environment_id uuid NOT NULL, application_id uuid NOT NULL,
    provider_account_id uuid NOT NULL, currency text NOT NULL,
request_type text NOT NULL CHECK(request_type IN ('CREATE_PAYMENT','CANCEL_PAYMENT')),
    idempotency_key text NOT NULL, request_fingerprint text NOT NULL, payment_id uuid NOT NULL, operation_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version>=0),
    FOREIGN KEY (organization_id,environment_id,application_id) REFERENCES platform.application(organization_id,environment_id,id),
    FOREIGN KEY (organization_id,environment_id,provider_account_id) REFERENCES platform.provider_account(organization_id,environment_id,id),
    FOREIGN KEY (organization_id,environment_id,currency) REFERENCES platform.org_currency_enabled(organization_id,environment_id,currency),
    UNIQUE (organization_id,environment_id,application_id,provider_account_id,currency,id),
    FOREIGN KEY (organization_id,environment_id,application_id,provider_account_id,currency,payment_id) REFERENCES platform.payment(organization_id,environment_id,application_id,provider_account_id,currency,id),
    FOREIGN KEY (organization_id,environment_id,application_id,provider_account_id,currency,payment_id,operation_id) REFERENCES platform.provider_operation(organization_id,environment_id,application_id,provider_account_id,currency,payment_id,id),
    UNIQUE (organization_id,environment_id,application_id,request_type,idempotency_key)
);
CREATE TABLE platform.recovery_task (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL, environment_id uuid NOT NULL, application_id uuid NOT NULL,
    provider_account_id uuid NOT NULL, currency text NOT NULL,
operation_id uuid NOT NULL, status text NOT NULL DEFAULT 'READY' CHECK(status IN ('READY','WAITING','DONE')),
    available_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version>=0),
    FOREIGN KEY (organization_id,environment_id,application_id) REFERENCES platform.application(organization_id,environment_id,id),
    FOREIGN KEY (organization_id,environment_id,provider_account_id) REFERENCES platform.provider_account(organization_id,environment_id,id),
    FOREIGN KEY (organization_id,environment_id,currency) REFERENCES platform.org_currency_enabled(organization_id,environment_id,currency),
    UNIQUE (organization_id,environment_id,application_id,provider_account_id,currency,id),
    FOREIGN KEY (organization_id,environment_id,application_id,provider_account_id,currency,operation_id) REFERENCES platform.provider_operation(organization_id,environment_id,application_id,provider_account_id,currency,id),
    UNIQUE(operation_id)
);
CREATE TABLE platform.payment_attempt (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL, environment_id uuid NOT NULL, application_id uuid NOT NULL,
    provider_account_id uuid NOT NULL, currency text NOT NULL,
payment_id uuid NOT NULL, provider_object_id text NOT NULL, provider_status text NOT NULL,
    payment_intent_id text NOT NULL, captured boolean NOT NULL, amount_minor bigint NOT NULL CHECK(amount_minor>=0),
    amount_captured_minor bigint CHECK(amount_captured_minor>=0), balance_transaction_id text,
    failure_code text, provider_created_at timestamptz, fetched_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version>=0),
    FOREIGN KEY (organization_id,environment_id,application_id) REFERENCES platform.application(organization_id,environment_id,id),
    FOREIGN KEY (organization_id,environment_id,provider_account_id) REFERENCES platform.provider_account(organization_id,environment_id,id),
    FOREIGN KEY (organization_id,environment_id,currency) REFERENCES platform.org_currency_enabled(organization_id,environment_id,currency),
    UNIQUE (organization_id,environment_id,application_id,provider_account_id,currency,id),
    FOREIGN KEY (organization_id,environment_id,application_id,provider_account_id,currency,payment_id) REFERENCES platform.payment(organization_id,environment_id,application_id,provider_account_id,currency,id),
    UNIQUE (organization_id,environment_id,provider_account_id,provider_object_id)
);
CREATE TABLE platform.provider_observation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL, environment_id uuid NOT NULL, application_id uuid NOT NULL,
    provider_account_id uuid NOT NULL, currency text NOT NULL,
operation_id uuid NOT NULL, payment_id uuid NOT NULL, lease_token uuid,
    call_type text NOT NULL, classification text NOT NULL, external_object_id text,
    evidence jsonb NOT NULL CHECK(jsonb_typeof(evidence)='object'),
    fact jsonb CHECK(fact IS NULL OR jsonb_typeof(fact) IN ('object','array')),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version>=0),
    FOREIGN KEY (organization_id,environment_id,application_id) REFERENCES platform.application(organization_id,environment_id,id),
    FOREIGN KEY (organization_id,environment_id,provider_account_id) REFERENCES platform.provider_account(organization_id,environment_id,id),
    FOREIGN KEY (organization_id,environment_id,currency) REFERENCES platform.org_currency_enabled(organization_id,environment_id,currency),
    UNIQUE (organization_id,environment_id,application_id,provider_account_id,currency,id),
    FOREIGN KEY (organization_id,environment_id,application_id,provider_account_id,currency,payment_id) REFERENCES platform.payment(organization_id,environment_id,application_id,provider_account_id,currency,id),
    FOREIGN KEY (organization_id,environment_id,application_id,provider_account_id,currency,payment_id,operation_id) REFERENCES platform.provider_operation(organization_id,environment_id,application_id,provider_account_id,currency,payment_id,id)
);
CREATE INDEX prepared_payment_operations ON platform.provider_operation(created_at) WHERE status='PREPARED';
CREATE INDEX payment_observation_anchor ON platform.provider_observation(external_object_id) WHERE external_object_id IS NOT NULL;
CREATE FUNCTION platform.pr06_immutable_intent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME='business_order' OR TG_TABLE_NAME='api_idempotency_record' OR TG_TABLE_NAME='provider_observation' THEN
        RAISE EXCEPTION 'Immutable payment intent/evidence' USING ERRCODE='23514';
    END IF;
    IF (NEW.organization_id,NEW.environment_id,NEW.application_id,NEW.provider_account_id,NEW.currency,NEW.operation_target,NEW.payment_id,NEW.refund_id,NEW.operation_type,NEW.operation_key,NEW.request_fingerprint,NEW.command,NEW.expires_at)
       IS DISTINCT FROM (OLD.organization_id,OLD.environment_id,OLD.application_id,OLD.provider_account_id,OLD.currency,OLD.operation_target,OLD.payment_id,OLD.refund_id,OLD.operation_type,OLD.operation_key,OLD.request_fingerprint,OLD.command,OLD.expires_at) THEN
       RAISE EXCEPTION 'Immutable provider command' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER immutable_order BEFORE UPDATE OR DELETE ON platform.business_order FOR EACH ROW EXECUTE FUNCTION platform.pr06_immutable_intent();
CREATE TRIGGER immutable_api_key BEFORE UPDATE OR DELETE ON platform.api_idempotency_record FOR EACH ROW EXECUTE FUNCTION platform.pr06_immutable_intent();
CREATE TRIGGER immutable_observation BEFORE UPDATE OR DELETE ON platform.provider_observation FOR EACH ROW EXECUTE FUNCTION platform.pr06_immutable_intent();
CREATE TRIGGER immutable_provider_command BEFORE UPDATE ON platform.provider_operation FOR EACH ROW EXECUTE FUNCTION platform.pr06_immutable_intent();
DO $$
DECLARE t text;
BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='payments_app') THEN
  FOREACH t IN ARRAY ARRAY['business_order','payment','api_idempotency_record','provider_operation','recovery_task','payment_attempt','provider_observation'] LOOP
   EXECUTE format('REVOKE ALL ON platform.%I FROM payments_app',t);
   EXECUTE format('GRANT SELECT,INSERT ON platform.%I TO payments_app',t);
  END LOOP;
  GRANT UPDATE ON platform.payment,platform.provider_operation,platform.recovery_task,platform.payment_attempt TO payments_app;
 END IF;
END $$;

CREATE FUNCTION platform.pr06_payment_identity_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.organization_id,NEW.environment_id,NEW.application_id,NEW.provider_account_id,NEW.currency,NEW.business_order_id,NEW.amount_minor,NEW.expires_at)
 IS DISTINCT FROM (OLD.organization_id,OLD.environment_id,OLD.application_id,OLD.provider_account_id,OLD.currency,OLD.business_order_id,OLD.amount_minor,OLD.expires_at) THEN
 RAISE EXCEPTION 'Immutable payment identity and amount' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_payment_identity BEFORE UPDATE ON platform.payment FOR EACH ROW EXECUTE FUNCTION platform.pr06_payment_identity_guard();

CREATE FUNCTION platform.pr06_attempt_identity_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.organization_id,NEW.environment_id,NEW.application_id,NEW.provider_account_id,NEW.currency,NEW.payment_id,NEW.payment_intent_id,NEW.provider_object_id,NEW.amount_minor)
 IS DISTINCT FROM (OLD.organization_id,OLD.environment_id,OLD.application_id,OLD.provider_account_id,OLD.currency,OLD.payment_id,OLD.payment_intent_id,OLD.provider_object_id,OLD.amount_minor)
 OR (OLD.captured AND (NOT NEW.captured OR NEW.provider_status<>OLD.provider_status OR NEW.amount_captured_minor IS DISTINCT FROM OLD.amount_captured_minor)) THEN
 RAISE EXCEPTION 'Immutable attempt identity and captured fact' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_attempt_identity BEFORE UPDATE ON platform.payment_attempt FOR EACH ROW EXECUTE FUNCTION platform.pr06_attempt_identity_guard();
