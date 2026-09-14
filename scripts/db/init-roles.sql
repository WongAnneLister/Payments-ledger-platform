-- 数据库角色分离（PR01）
--
-- 本脚本由 Compose 在 **容器首次初始化** 时执行一次，且只作用于本项目容器内的数据库。
-- 它不连接、不修改、不删除任何外部数据库。
--
-- 设计依据：ADR-0004、docs/ledger/accounts.md §6、docs/runbook/database-roles.md
--   迁移角色  payments_migrator ：持有 DDL 权限，执行 Flyway
--   应用角色  payments_app      ：运行时连接；账本表将只有 INSERT（PR03 落地）
--
-- 本地开发口令来自 .env（见 .env.example）。生产部署不使用本脚本，
-- 也不把口令写进版本库。

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'payments_migrator') THEN
        EXECUTE format('CREATE ROLE payments_migrator LOGIN PASSWORD %L',
                       coalesce(current_setting('custom.migration_password', true), 'payments_migrator_pw'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'payments_app') THEN
        EXECUTE format('CREATE ROLE payments_app LOGIN PASSWORD %L',
                       coalesce(current_setting('custom.app_password', true), 'payments_app_pw'));
    END IF;
END
$$;

-- 库名由 Compose 参数化，这里用 current_database() 而不是硬编码
DO $$
DECLARE
    db text := current_database();
BEGIN
    EXECUTE format('GRANT CREATE, CONNECT ON DATABASE %I TO payments_migrator', db);
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO payments_app', db);
END
$$;

-- 应用角色不得在 public 下建对象
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- platform schema 由 V1 baseline 迁移创建。这里先幂等建出来，
-- 以便立即登记默认权限（ALTER DEFAULT PRIVILEGES 要求 schema 已存在）。
CREATE SCHEMA IF NOT EXISTS platform AUTHORIZATION payments_migrator;
GRANT USAGE ON SCHEMA platform TO payments_app;

-- 迁移角色今后新建的表，默认授予应用角色 SELECT + INSERT —— **不含 UPDATE / DELETE**。
ALTER DEFAULT PRIVILEGES FOR ROLE payments_migrator IN SCHEMA platform
    GRANT SELECT, INSERT ON TABLES TO payments_app;
ALTER DEFAULT PRIVILEGES FOR ROLE payments_migrator IN SCHEMA platform
    GRANT USAGE, SELECT ON SEQUENCES TO payments_app;

-- 说明：
--   * 需要 UPDATE 的非账本表（provider_operation 租约字段、各类控制行）
--     由相应 PR 的迁移**逐表**显式 GRANT UPDATE，不整库放开。
--   * 账本表 journal / journal_entry 一律保持 INSERT-only，
--     由 PR03 使用实际应用角色验证封存防护（AC-V1-17）。
