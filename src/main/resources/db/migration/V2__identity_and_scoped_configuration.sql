-- PR02: trusted identities and controlled configuration. V1 remains immutable.
CREATE TABLE platform.organization (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), code text NOT NULL UNIQUE,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
);
CREATE TABLE platform.environment_config (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL REFERENCES platform.organization(id),
    code text NOT NULL, livemode boolean NOT NULL,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (organization_id,code), UNIQUE (organization_id,id), UNIQUE (organization_id,id,livemode)
);
CREATE TABLE platform.application (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, environment_id uuid NOT NULL,
    code text NOT NULL, status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY (organization_id,environment_id) REFERENCES platform.environment_config(organization_id,id),
    UNIQUE (organization_id,environment_id,code), UNIQUE (organization_id,environment_id,id)
);
CREATE TABLE platform.provider_account (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, environment_id uuid NOT NULL,
    provider text NOT NULL CHECK (provider IN ('FAKE','STRIPE')),
    provider_account_ref text NOT NULL UNIQUE CHECK (length(provider_account_ref)>0), livemode boolean NOT NULL,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY (organization_id,environment_id,livemode) REFERENCES platform.environment_config(organization_id,id,livemode),
    UNIQUE (organization_id,environment_id,id), UNIQUE (organization_id,environment_id,provider,provider_account_ref)
);
CREATE TABLE platform.currency_config (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), code text NOT NULL UNIQUE CHECK (code ~ '^[A-Z]{3}$'),
    minor_unit_exponent smallint NOT NULL CHECK (minor_unit_exponent BETWEEN 0 AND 4),
    CHECK (code <> 'USD' OR minor_unit_exponent=2), CHECK (code <> 'JPY' OR minor_unit_exponent=0),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
);
CREATE TABLE platform.org_currency_enabled (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, environment_id uuid NOT NULL,
    currency text NOT NULL REFERENCES platform.currency_config(code),
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY (organization_id,environment_id) REFERENCES platform.environment_config(organization_id,id),
    UNIQUE (organization_id,environment_id,currency)
);
CREATE TABLE platform.application_credential (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), key_id text NOT NULL,
    organization_id uuid NOT NULL, environment_id uuid NOT NULL, application_id uuid NOT NULL, provider_account_id uuid NOT NULL,
    secret_hash text NOT NULL CHECK (secret_hash ~ '^\$2[aby]\$1[0-4]\$[./A-Za-z0-9]{53}$'),
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
    not_before timestamptz NOT NULL, expires_at timestamptz NOT NULL CHECK (expires_at>not_before),
    granted_scopes text[] NOT NULL DEFAULT '{}',
    CHECK (granted_scopes <@ ARRAY['payments:read','payments:write','refunds:read','refunds:write']::text[]
        AND array_position(granted_scopes,NULL) IS NULL),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY (organization_id,environment_id,application_id) REFERENCES platform.application(organization_id,environment_id,id),
    FOREIGN KEY (organization_id,environment_id,provider_account_id) REFERENCES platform.provider_account(organization_id,environment_id,id),
    UNIQUE (application_id,key_id), UNIQUE (organization_id,environment_id,id)
);
CREATE TABLE platform.credential_currency (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), credential_id uuid NOT NULL, organization_id uuid NOT NULL,
    environment_id uuid NOT NULL, currency text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY (organization_id,environment_id,credential_id) REFERENCES platform.application_credential(organization_id,environment_id,id),
    FOREIGN KEY (organization_id,environment_id,currency) REFERENCES platform.org_currency_enabled(organization_id,environment_id,currency),
    UNIQUE (credential_id,currency)
);
CREATE TABLE platform.ops_identity (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL REFERENCES platform.organization(id),
    subject text NOT NULL, role text NOT NULL CHECK (role IN ('OPS_VIEWER','OPS_OPERATOR','OPS_REVIEWER','OPS_ADMIN','PARTNER_VIEWER')),
    partner_id uuid, CHECK ((role='PARTNER_VIEWER') = (partner_id IS NOT NULL)),
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (organization_id,subject), UNIQUE (organization_id,id)
);
CREATE TABLE platform.ops_credential (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), ops_identity_id uuid NOT NULL, organization_id uuid NOT NULL,
    environment_id uuid NOT NULL, key_id text NOT NULL,
    secret_hash text NOT NULL CHECK (secret_hash ~ '^\$2[aby]\$1[0-4]\$[./A-Za-z0-9]{53}$'),
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
    not_before timestamptz NOT NULL, expires_at timestamptz NOT NULL CHECK (expires_at>not_before),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY (organization_id,ops_identity_id) REFERENCES platform.ops_identity(organization_id,id),
    FOREIGN KEY (organization_id,environment_id) REFERENCES platform.environment_config(organization_id,id),
    UNIQUE (ops_identity_id,key_id)
);
CREATE TABLE platform.share_policy_fixture (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, environment_id uuid NOT NULL,
    product_ref text NOT NULL, policy_version integer NOT NULL CHECK (policy_version>0),
    partner_ref text NOT NULL, share_basis_points integer NOT NULL CHECK (share_basis_points BETWEEN 0 AND 10000),
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    valid_from timestamptz NOT NULL DEFAULT now(), valid_until timestamptz NOT NULL DEFAULT 'infinity',
    CHECK (valid_until>valid_from),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY (organization_id,environment_id) REFERENCES platform.environment_config(organization_id,id),
    UNIQUE (organization_id,environment_id,product_ref,policy_version), UNIQUE (organization_id,environment_id,product_ref,id)
);
CREATE TABLE platform.price_fixture (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, environment_id uuid NOT NULL,
    product_ref text NOT NULL, price_version integer NOT NULL CHECK (price_version>0),
    currency text NOT NULL, amount_minor bigint NOT NULL CHECK (amount_minor>0), share_policy_id uuid NOT NULL,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    valid_from timestamptz NOT NULL DEFAULT now(), valid_until timestamptz NOT NULL DEFAULT 'infinity',
    CHECK (valid_until>valid_from),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY (organization_id,environment_id,currency) REFERENCES platform.org_currency_enabled(organization_id,environment_id,currency),
    FOREIGN KEY (organization_id,environment_id,product_ref,share_policy_id) REFERENCES platform.share_policy_fixture(organization_id,environment_id,product_ref,id),
    UNIQUE (organization_id,environment_id,product_ref,price_version)
);
-- Configuration is changed only by controlled provisioning, never ordinary request code.
-- Compose creates this role. Standalone migration tests need not pre-create it.
DO $$
DECLARE t text;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='payments_app') THEN
        FOREACH t IN ARRAY ARRAY['organization','environment_config','application','provider_account',
            'currency_config','org_currency_enabled','application_credential','credential_currency',
            'ops_identity','ops_credential','share_policy_fixture','price_fixture'] LOOP
            EXECUTE format('REVOKE ALL ON platform.%I FROM payments_app',t);
            EXECUTE format('GRANT SELECT ON platform.%I TO payments_app',t);
        END LOOP;
    END IF;
END $$;
