ALTER TABLE platform.payment ADD COLUMN unresolved_since timestamptz;
UPDATE platform.payment SET unresolved_since=updated_at WHERE resolution_status IN ('UNKNOWN','CONFLICT');
-- Extend the original operation/task identities; every task remains tied to its scoped payment.
ALTER TABLE platform.provider_operation
 ADD COLUMN first_send_at timestamptz,
 ADD COLUMN retry_until timestamptz,
 ADD COLUMN escalation_reason text,
 ADD COLUMN escalated_at timestamptz,
 ADD COLUMN age_alert_at timestamptz;
-- Existing uncertain PR06 operations have no verified retry horizon. Never infer a fresh window.
UPDATE platform.provider_operation SET first_send_at=created_at WHERE attempt_count>0;
ALTER TABLE platform.provider_operation ADD CONSTRAINT recovery_attempt_count CHECK(attempt_count>=0);
ALTER TABLE platform.provider_operation ADD CONSTRAINT recovery_horizon_pair CHECK(retry_until IS NULL OR (first_send_at IS NOT NULL AND retry_until>first_send_at));
ALTER TABLE platform.recovery_task
 ADD COLUMN task_type text NOT NULL DEFAULT 'OPERATION_RECOVERY' CHECK(task_type='OPERATION_RECOVERY'),
 ADD COLUMN payment_id uuid,
 ADD COLUMN lease_token uuid,
 ADD COLUMN lease_until timestamptz,
 ADD COLUMN attempt_count integer NOT NULL DEFAULT 0 CHECK(attempt_count>=0),
 ADD COLUMN reason text,
 ADD COLUMN escalated_at timestamptz,
 ADD COLUMN age_alert_at timestamptz;
UPDATE platform.recovery_task t SET payment_id=o.payment_id FROM platform.provider_operation o WHERE o.id=t.operation_id;
ALTER TABLE platform.recovery_task ALTER COLUMN payment_id SET NOT NULL;
ALTER TABLE platform.recovery_task ADD CONSTRAINT recovery_payment_operation_fk
 FOREIGN KEY(organization_id,environment_id,application_id,provider_account_id,currency,payment_id,operation_id)
 REFERENCES platform.provider_operation(organization_id,environment_id,application_id,provider_account_id,currency,payment_id,id);
ALTER TABLE platform.recovery_task DROP CONSTRAINT recovery_task_status_check;
ALTER TABLE platform.recovery_task ADD CONSTRAINT recovery_task_status_check CHECK(status IN ('READY','WAITING','RUNNING','DONE','ESCALATED'));
UPDATE platform.recovery_task t SET status='WAITING' FROM platform.provider_operation o JOIN platform.payment p ON p.id=o.payment_id
 WHERE o.id=t.operation_id AND t.status='DONE' AND (p.payment_status NOT IN ('SUCCEEDED','CANCELED') OR p.financial_status='PENDING_EVIDENCE');
CREATE INDEX recovery_due ON platform.recovery_task(available_at,operation_id) WHERE status IN ('READY','WAITING','RUNNING');
CREATE TABLE platform.recovery_account_limit (
 organization_id uuid NOT NULL, environment_id uuid NOT NULL, provider_account_id uuid NOT NULL,
 next_batch_at timestamptz NOT NULL,
 PRIMARY KEY(organization_id,environment_id,provider_account_id),
 FOREIGN KEY(organization_id,environment_id,provider_account_id) REFERENCES platform.provider_account(organization_id,environment_id,id)
);
CREATE FUNCTION platform.pr07_recovery_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='provider_operation' THEN
  IF OLD.first_send_at IS NOT NULL AND (NEW.first_send_at IS DISTINCT FROM OLD.first_send_at OR NEW.retry_until IS DISTINCT FROM OLD.retry_until) THEN
   RAISE EXCEPTION 'Recovery horizon is immutable after first send' USING ERRCODE='23514';
  END IF;
  IF OLD.age_alert_at IS NOT NULL AND NEW.age_alert_at IS DISTINCT FROM OLD.age_alert_at THEN
   RAISE EXCEPTION 'Recovery age alert is durable' USING ERRCODE='23514';
  END IF;
 ELSE
  IF (NEW.organization_id,NEW.environment_id,NEW.application_id,NEW.provider_account_id,NEW.currency,NEW.payment_id,NEW.operation_id,NEW.task_type)
   IS DISTINCT FROM (OLD.organization_id,OLD.environment_id,OLD.application_id,OLD.provider_account_id,OLD.currency,OLD.payment_id,OLD.operation_id,OLD.task_type) THEN
   RAISE EXCEPTION 'Immutable recovery resource association' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER recovery_horizon_immutable BEFORE UPDATE ON platform.provider_operation FOR EACH ROW EXECUTE FUNCTION platform.pr07_recovery_guard();
CREATE TRIGGER recovery_resource_immutable BEFORE UPDATE ON platform.recovery_task FOR EACH ROW EXECUTE FUNCTION platform.pr07_recovery_guard();
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='payments_app') THEN
  GRANT SELECT,INSERT,UPDATE ON platform.recovery_account_limit TO payments_app;
 END IF;
END $$;
