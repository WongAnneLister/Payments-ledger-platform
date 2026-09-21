-- PR03: accepted immutable financial facts, sealed double-entry journals and derived balances.
-- The migration owner owns the sole privileged posting function; payments_app must never
-- be a member of the migration-owner role. No caller-controlled setting grants authority.
CREATE TABLE platform.ledger_account (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL, environment_id uuid NOT NULL, currency text NOT NULL,
    account_purpose text NOT NULL, source_domain text NOT NULL,
    provider_account_id uuid, balance_domain text,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY (organization_id,environment_id,currency)
        REFERENCES platform.org_currency_enabled(organization_id,environment_id,currency),
    FOREIGN KEY (organization_id,environment_id,provider_account_id)
        REFERENCES platform.provider_account(organization_id,environment_id,id),
    UNIQUE (organization_id,environment_id,currency,id),
    UNIQUE NULLS NOT DISTINCT (organization_id,environment_id,currency,account_purpose,source_domain,provider_account_id,balance_domain),
    CHECK (
        (account_purpose IN ('PSP_FUNDS','BANK_PAYOUT_IN_TRANSIT','DISPUTE_RECEIVABLE',
            'PSP_FEE_EXPENSE','DISPUTE_FEE_EXPENSE','SUSPENSE') AND source_domain='PROVIDER'
            AND provider_account_id IS NOT NULL AND balance_domain IS NOT NULL AND balance_domain='PAYMENTS')
        OR (account_purpose IN ('BANK_CASH','BANK_FEE_EXPENSE') AND source_domain='SIMULATED_BANK'
            AND provider_account_id IS NULL AND balance_domain IS NULL)
        OR (account_purpose IN ('SALES_REVENUE','SALES_RETURNS','DISPUTE_LOSS',
            'PARTNER_SHARE_EXPENSE','PARTNER_PAYABLE','OPENING_EQUITY') AND source_domain='INTERNAL'
            AND provider_account_id IS NULL AND balance_domain IS NULL)
    )
);
CREATE TABLE platform.financial_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL, environment_id uuid NOT NULL, currency text NOT NULL,
    provider_account_id uuid, event_key text NOT NULL CHECK (length(btrim(event_key))>0),
    event_type text NOT NULL CHECK (length(btrim(event_type))>0),
    source_domain text NOT NULL CHECK (source_domain IN ('PROVIDER','INTERNAL','SIMULATED_BANK')),
    source_ref text NOT NULL CHECK (length(btrim(source_ref))>0),
    facts jsonb NOT NULL CHECK (jsonb_typeof(facts)='object'),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version=0),
    CHECK (source_domain<>'PROVIDER' OR provider_account_id IS NOT NULL),
    FOREIGN KEY (organization_id,environment_id,currency)
        REFERENCES platform.org_currency_enabled(organization_id,environment_id,currency),
    FOREIGN KEY (organization_id,environment_id,provider_account_id)
        REFERENCES platform.provider_account(organization_id,environment_id,id),
    UNIQUE (organization_id,environment_id,event_key),
    UNIQUE (organization_id,environment_id,currency,id)
);
CREATE TABLE platform.journal (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL, environment_id uuid NOT NULL, currency text NOT NULL,
    financial_event_id uuid NOT NULL UNIQUE,
    reversal_of_journal_id uuid UNIQUE,
    status text NOT NULL DEFAULT 'POSTED' CHECK (status='POSTED'),
    -- Deliberately no default: the INSERT trigger rejects a supplied value and stamps it.
    construction_txid bigint NOT NULL,
    sealed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version=0),
    FOREIGN KEY (organization_id,environment_id,currency,financial_event_id)
        REFERENCES platform.financial_event(organization_id,environment_id,currency,id),
    UNIQUE (organization_id,environment_id,currency,id),
    FOREIGN KEY (organization_id,environment_id,currency,reversal_of_journal_id)
        REFERENCES platform.journal(organization_id,environment_id,currency,id),
    CHECK (id IS DISTINCT FROM reversal_of_journal_id)
);
CREATE TABLE platform.journal_entry (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), journal_id uuid NOT NULL,
    ledger_account_id uuid NOT NULL,
    organization_id uuid NOT NULL, environment_id uuid NOT NULL, currency text NOT NULL,
    side text NOT NULL CHECK (side IN ('DEBIT','CREDIT')),
    amount_minor bigint NOT NULL CHECK (amount_minor>0), line_no integer NOT NULL CHECK (line_no>0),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version=0),
    UNIQUE (journal_id,line_no),
    FOREIGN KEY (organization_id,environment_id,currency,journal_id)
        REFERENCES platform.journal(organization_id,environment_id,currency,id),
    FOREIGN KEY (organization_id,environment_id,currency,ledger_account_id)
        REFERENCES platform.ledger_account(organization_id,environment_id,currency,id)
);
CREATE INDEX ix_entry_account ON platform.journal_entry(ledger_account_id,journal_id);
CREATE TABLE platform.balance_projection (
    ledger_account_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL, environment_id uuid NOT NULL, currency text NOT NULL,
    balance_minor bigint NOT NULL DEFAULT 0, version bigint NOT NULL DEFAULT 0 CHECK (version>=0),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id,environment_id,currency,ledger_account_id)
        REFERENCES platform.ledger_account(organization_id,environment_id,currency,id)
);

CREATE FUNCTION platform.reject_ledger_mutation() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,platform,pg_temp AS $$
BEGIN
    RAISE EXCEPTION 'accepted ledger content is immutable' USING ERRCODE='23514';
END $$;
CREATE TRIGGER financial_event_immutable BEFORE UPDATE OR DELETE ON platform.financial_event
    FOR EACH ROW EXECUTE FUNCTION platform.reject_ledger_mutation();
CREATE TRIGGER journal_entry_immutable BEFORE UPDATE OR DELETE ON platform.journal_entry
    FOR EACH ROW EXECUTE FUNCTION platform.reject_ledger_mutation();

CREATE FUNCTION platform.guard_journal_construction() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,platform,pg_temp AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.sealed_at IS NOT NULL OR NEW.construction_txid IS NOT NULL THEN
            RAISE EXCEPTION 'construction and sealing are database controlled' USING ERRCODE='23514';
        END IF;
        NEW.construction_txid := txid_current();
        RETURN NEW;
    END IF;
    -- Invoker security intentionally observes the definer of post_ledger. Runtime SQL
    -- cannot assume that role, update the table or supply an equivalent capability.
    IF TG_OP='UPDATE' AND current_user=pg_get_userbyid((SELECT relowner FROM pg_class WHERE oid=TG_RELID))
       AND OLD.sealed_at IS NULL AND NEW.sealed_at IS NOT NULL
       AND OLD.construction_txid=txid_current()
       AND (to_jsonb(NEW)-'sealed_at')=(to_jsonb(OLD)-'sealed_at') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'journal cannot be altered after construction' USING ERRCODE='23514';
END $$;
CREATE TRIGGER journal_construction BEFORE INSERT OR UPDATE OR DELETE ON platform.journal
    FOR EACH ROW EXECUTE FUNCTION platform.guard_journal_construction();

CREATE FUNCTION platform.guard_entry_construction() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,platform,pg_temp AS $$
DECLARE j platform.journal%ROWTYPE; a platform.ledger_account%ROWTYPE; provider uuid;
BEGIN
    SELECT * INTO j FROM platform.journal WHERE id=NEW.journal_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'journal does not exist' USING ERRCODE='23503'; END IF;
    IF j.sealed_at IS NOT NULL OR j.construction_txid<>txid_current() THEN
        RAISE EXCEPTION 'entries require an unsealed journal in its construction transaction' USING ERRCODE='23514';
    END IF;
    SELECT * INTO a FROM platform.ledger_account WHERE id=NEW.ledger_account_id;
    SELECT provider_account_id INTO provider FROM platform.financial_event WHERE id=j.financial_event_id;
    IF a.source_domain='PROVIDER' AND (a.provider_account_id IS DISTINCT FROM provider OR a.balance_domain<>'PAYMENTS') THEN
        RAISE EXCEPTION 'entry provider scope is incompatible with financial event' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER journal_entry_construction BEFORE INSERT ON platform.journal_entry
    FOR EACH ROW EXECUTE FUNCTION platform.guard_entry_construction();

CREATE FUNCTION platform.check_journal_final() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,platform,pg_temp AS $$
DECLARE j platform.journal%ROWTYPE; n bigint; debits numeric; credits numeric;
BEGIN
    SELECT * INTO j FROM platform.journal WHERE id=NEW.id;
    SELECT count(*),coalesce(sum(amount_minor) FILTER (WHERE side='DEBIT'),0),
           coalesce(sum(amount_minor) FILTER (WHERE side='CREDIT'),0)
        INTO n,debits,credits FROM platform.journal_entry WHERE journal_id=j.id;
    IF n<2 OR debits<>credits OR debits>9223372036854775807 OR credits>9223372036854775807 THEN
        RAISE EXCEPTION 'journal requires at least two balanced positive entries within bigint totals' USING ERRCODE='23514';
    END IF;
    IF j.sealed_at IS NULL THEN RAISE EXCEPTION 'journal must be sealed by posting function' USING ERRCODE='23514'; END IF;
    IF j.reversal_of_journal_id IS NOT NULL THEN
        IF EXISTS (
            WITH original AS (
                SELECT ledger_account_id,CASE side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END side,sum(amount_minor) amount
                FROM platform.journal_entry WHERE journal_id=j.reversal_of_journal_id GROUP BY ledger_account_id,side
            ), reversal AS (
                SELECT ledger_account_id,side,sum(amount_minor) amount FROM platform.journal_entry
                WHERE journal_id=j.id GROUP BY ledger_account_id,side
            )
            (SELECT * FROM original EXCEPT SELECT * FROM reversal)
            UNION ALL (SELECT * FROM reversal EXCEPT SELECT * FROM original)
        ) THEN RAISE EXCEPTION 'reversal is not the exact inverse' USING ERRCODE='23514'; END IF;
        IF (SELECT e.provider_account_id FROM platform.financial_event e WHERE e.id=j.financial_event_id)
           IS DISTINCT FROM (SELECT e.provider_account_id FROM platform.journal o JOIN platform.financial_event e
               ON e.id=o.financial_event_id WHERE o.id=j.reversal_of_journal_id) THEN
            RAISE EXCEPTION 'reversal provider scope mismatch' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER journal_final AFTER INSERT OR UPDATE ON platform.journal
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_journal_final();
CREATE FUNCTION platform.check_event_journal() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,platform,pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM platform.journal WHERE financial_event_id=NEW.id) THEN
        RAISE EXCEPTION 'accepted event requires exactly one journal' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER event_journal AFTER INSERT ON platform.financial_event
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_event_journal();

-- JSON is normalized as a sorted multiset of exact lines; line order is irrelevant,
-- but splitting/merging an original line changes the accepted posting evidence.
CREATE FUNCTION platform.post_ledger(
    p_org uuid,p_env uuid,p_currency text,p_provider uuid,p_event_key text,p_event_type text,
    p_source_domain text,p_source_ref text,p_facts jsonb,p_entries jsonb,p_reversal_of uuid DEFAULT NULL
) RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,platform,pg_temp AS $$
<<posting>>
DECLARE
    event_id uuid; journal_id uuid; prior platform.financial_event%ROWTYPE;
    normalized jsonb; old_entries jsonb; line jsonb; account_ids uuid[]; account_id uuid;
    n integer; debits numeric; credits numeric; original_provider uuid; prior_reversal uuid;
    delta record;
BEGIN
    IF p_org IS NULL OR p_env IS NULL OR p_currency IS NULL OR p_event_key IS NULL OR btrim(p_event_key)=''
       OR p_event_type IS NULL OR btrim(p_event_type)='' OR p_source_ref IS NULL OR btrim(p_source_ref)=''
       OR p_source_domain IS NULL OR p_source_domain NOT IN ('PROVIDER','INTERNAL','SIMULATED_BANK')
       OR p_facts IS NULL OR jsonb_typeof(p_facts)<>'object'
       OR p_entries IS NULL OR jsonb_typeof(p_entries)<>'array' THEN
        RAISE EXCEPTION 'invalid posting evidence or entries' USING ERRCODE='23514';
    END IF;
    FOR line IN SELECT value FROM jsonb_array_elements(p_entries) LOOP
        IF jsonb_typeof(line)<>'object' OR NOT (line ?& ARRAY['accountId','side','amount'])
           OR (line-ARRAY['accountId','side','amount'])<>'{}'::jsonb
           OR jsonb_typeof(line->'accountId')<>'string' OR jsonb_typeof(line->'side')<>'string'
           OR jsonb_typeof(line->'amount')<>'number'
           OR (line->>'side') NOT IN ('DEBIT','CREDIT')
           OR (line->>'amount')!~'^[0-9]+$'
           OR (line->>'amount')::numeric NOT BETWEEN 1 AND 9223372036854775807 THEN
            RAISE EXCEPTION 'invalid entry' USING ERRCODE='23514';
        END IF;
        -- Validate UUID syntax before any accepted objects are created.
        account_id := (line->>'accountId')::uuid;
    END LOOP;
    SELECT jsonb_agg(jsonb_build_object('accountId',(value->>'accountId')::uuid,
               'side',value->>'side','amount',(value->>'amount')::bigint)
               ORDER BY (value->>'accountId')::uuid,value->>'side',(value->>'amount')::bigint),
           count(*),sum((value->>'amount')::numeric) FILTER (WHERE value->>'side'='DEBIT'),
           sum((value->>'amount')::numeric) FILTER (WHERE value->>'side'='CREDIT'),
           array_agg(DISTINCT (value->>'accountId')::uuid ORDER BY (value->>'accountId')::uuid)
        INTO normalized,n,debits,credits,account_ids FROM jsonb_array_elements(p_entries);
    IF n<2 OR debits IS NULL OR credits IS NULL OR debits<>credits
       OR debits>9223372036854775807 OR credits>9223372036854775807 THEN
        RAISE EXCEPTION 'posting must balance within bigint totals' USING ERRCODE='23514';
    END IF;

    -- rank 70: the unique accepted event serializes same-key callers before account locks.
    INSERT INTO platform.financial_event(organization_id,environment_id,currency,provider_account_id,
        event_key,event_type,source_domain,source_ref,facts)
        VALUES(p_org,p_env,p_currency,p_provider,p_event_key,p_event_type,p_source_domain,p_source_ref,p_facts)
        ON CONFLICT (organization_id,environment_id,event_key) DO NOTHING RETURNING id INTO event_id;
    IF event_id IS NULL THEN
        SELECT * INTO STRICT prior FROM platform.financial_event
            WHERE organization_id=p_org AND environment_id=p_env AND event_key=p_event_key;
        SELECT id,reversal_of_journal_id INTO journal_id,prior_reversal FROM platform.journal WHERE financial_event_id=prior.id;
        SELECT jsonb_agg(jsonb_build_object('accountId',ledger_account_id,'side',side,'amount',amount_minor)
            ORDER BY ledger_account_id,side,amount_minor) INTO old_entries
            FROM platform.journal_entry WHERE journal_entry.journal_id=posting.journal_id;
        IF prior.currency IS DISTINCT FROM p_currency OR prior.provider_account_id IS DISTINCT FROM p_provider
            OR prior.event_type IS DISTINCT FROM p_event_type OR prior.source_domain IS DISTINCT FROM p_source_domain
            OR prior.source_ref IS DISTINCT FROM p_source_ref OR prior.facts IS DISTINCT FROM p_facts
            OR prior_reversal IS DISTINCT FROM p_reversal_of OR old_entries IS DISTINCT FROM normalized
            OR journal_id IS NULL THEN
            RAISE EXCEPTION 'event key already accepted with different evidence' USING ERRCODE='P0003';
        END IF;
        RETURN journal_id;
    END IF;
    -- Recheck the test-only boundary for new facts, including configuration introduced
    -- after application startup. Historical exact replays above remain readable.
    IF EXISTS(SELECT 1 FROM platform.environment_config
        WHERE organization_id=p_org AND id=p_env AND livemode) THEN
        RAISE EXCEPTION 'live environments cannot post in this release' USING ERRCODE='23514';
    END IF;
    -- rank 80: PostgreSQL UUID order, independent of side, amount or caller array order.
    PERFORM id FROM platform.ledger_account WHERE id=ANY(account_ids)
        AND organization_id=p_org AND environment_id=p_env AND currency=p_currency ORDER BY id FOR UPDATE;
    GET DIAGNOSTICS n=ROW_COUNT;
    IF n<>cardinality(account_ids) THEN RAISE EXCEPTION 'scoped account not found' USING ERRCODE='P0002'; END IF;
    IF EXISTS(SELECT 1 FROM platform.ledger_account WHERE id=ANY(account_ids) AND source_domain='PROVIDER'
        AND (provider_account_id IS DISTINCT FROM p_provider OR balance_domain<>'PAYMENTS')) THEN
        RAISE EXCEPTION 'account provider does not match posting scope' USING ERRCODE='23514';
    END IF;
    IF p_reversal_of IS NOT NULL THEN
        SELECT e.provider_account_id INTO original_provider FROM platform.journal j
            JOIN platform.financial_event e ON e.id=j.financial_event_id
            WHERE j.id=p_reversal_of AND j.organization_id=p_org AND j.environment_id=p_env
              AND j.currency=p_currency AND j.sealed_at IS NOT NULL;
        IF NOT FOUND OR original_provider IS DISTINCT FROM p_provider THEN
            RAISE EXCEPTION 'scoped original journal not found' USING ERRCODE='P0002';
        END IF;
        IF EXISTS(SELECT 1 FROM platform.journal WHERE reversal_of_journal_id=p_reversal_of) THEN
            RAISE EXCEPTION 'original journal has already been reversed' USING ERRCODE='P0003';
        END IF;
        IF EXISTS(
            WITH original AS (
                SELECT ledger_account_id,CASE side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END side,sum(amount_minor) amount
                FROM platform.journal_entry WHERE journal_entry.journal_id=p_reversal_of GROUP BY ledger_account_id,side
            ), reversed AS (
                SELECT (value->>'accountId')::uuid account_id,value->>'side' side,sum((value->>'amount')::numeric) amount
                FROM jsonb_array_elements(normalized) GROUP BY (value->>'accountId')::uuid,value->>'side'
            )
            (SELECT * FROM original EXCEPT SELECT * FROM reversed)
            UNION ALL (SELECT * FROM reversed EXCEPT SELECT * FROM original)
        ) THEN RAISE EXCEPTION 'reversal is not the exact inverse' USING ERRCODE='23514'; END IF;
    END IF;
    BEGIN
        INSERT INTO platform.journal(organization_id,environment_id,currency,financial_event_id,reversal_of_journal_id)
            VALUES(p_org,p_env,p_currency,event_id,p_reversal_of) RETURNING id INTO journal_id;
    EXCEPTION WHEN unique_violation THEN
        RAISE EXCEPTION 'original journal has already been reversed' USING ERRCODE='P0003';
    END;
    INSERT INTO platform.journal_entry(journal_id,ledger_account_id,organization_id,environment_id,currency,side,amount_minor,line_no)
        SELECT journal_id,(value->>'accountId')::uuid,p_org,p_env,p_currency,value->>'side',
               (value->>'amount')::bigint,ordinality::integer FROM jsonb_array_elements(normalized) WITH ORDINALITY;
    UPDATE platform.journal SET sealed_at=clock_timestamp() WHERE id=journal_id;
    -- rank 85: account locks above serialize these per-account projection updates.
    FOR delta IN SELECT (value->>'accountId')::uuid id,
        sum(CASE value->>'side' WHEN 'DEBIT' THEN (value->>'amount')::numeric ELSE -(value->>'amount')::numeric END) amount
        FROM jsonb_array_elements(normalized) GROUP BY (value->>'accountId')::uuid ORDER BY (value->>'accountId')::uuid LOOP
        INSERT INTO platform.balance_projection(ledger_account_id,organization_id,environment_id,currency,balance_minor,version)
            VALUES(delta.id,p_org,p_env,p_currency,delta.amount::bigint,1)
            ON CONFLICT (ledger_account_id) DO UPDATE
                SET balance_minor=(platform.balance_projection.balance_minor::numeric+EXCLUDED.balance_minor)::bigint,
                    version=platform.balance_projection.version+1,updated_at=clock_timestamp();
    END LOOP;
    RETURN journal_id;
EXCEPTION
    WHEN numeric_value_out_of_range OR invalid_text_representation THEN
        RAISE EXCEPTION 'invalid or overflowing ledger amount or identifier' USING ERRCODE='23514';
END $$;

REVOKE ALL ON FUNCTION platform.reject_ledger_mutation(),platform.guard_journal_construction(),
    platform.guard_entry_construction(),platform.check_journal_final(),platform.check_event_journal() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform.post_ledger(uuid,uuid,text,uuid,text,text,text,text,jsonb,jsonb,uuid) FROM PUBLIC;
REVOKE ALL ON platform.ledger_account,platform.financial_event,platform.journal,platform.journal_entry,platform.balance_projection FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='payments_app') THEN
        REVOKE ALL ON platform.ledger_account,platform.financial_event,platform.journal,platform.journal_entry,platform.balance_projection FROM payments_app;
        GRANT USAGE ON SCHEMA platform TO payments_app;
        GRANT SELECT ON platform.ledger_account,platform.balance_projection TO payments_app;
        GRANT SELECT,INSERT ON platform.financial_event,platform.journal,platform.journal_entry TO payments_app;
        GRANT EXECUTE ON FUNCTION platform.post_ledger(uuid,uuid,text,uuid,text,text,text,text,jsonb,jsonb,uuid) TO payments_app;
    END IF;
END $$;
