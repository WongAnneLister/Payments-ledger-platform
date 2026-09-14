-- V1 baseline（PR01）
--
-- 范围纪律：本迁移**只建立基础设施**，不创建任何业务表。
-- payment / refund / journal / journal_entry / balance_projection 等业务表
-- 分别由 PR02、PR03、PR06、PR10、PR11 等后续 PR 追加迁移创建。
--
-- 已发布迁移不原地重写；需要修改约束时追加新迁移文件（路线图 §5.5）。

-- 业务对象统一放在独立 schema，便于与扩展、迁移工具的对象分离，
-- 也便于按 schema 授予应用运行角色的最小权限。
CREATE SCHEMA IF NOT EXISTS platform;

COMMENT ON SCHEMA platform IS
    'Payments ledger platform business objects. Created by PR01 baseline; tables added from PR02 onward.';

-- 迁移基线标记表：记录工程级基线信息，便于备份恢复演练（AC-V1-37）核对迁移起点。
-- 这不是业务表，不承载资金数据。
CREATE TABLE IF NOT EXISTS platform.schema_baseline (
    id               smallint     PRIMARY KEY DEFAULT 1,
    baseline_pr      text         NOT NULL,
    application_name text         NOT NULL,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_schema_baseline_single_row CHECK (id = 1)
);

COMMENT ON TABLE platform.schema_baseline IS
    'Single-row engineering baseline marker. Not a business table; carries no money data.';

INSERT INTO platform.schema_baseline (id, baseline_pr, application_name)
VALUES (1, 'PR01', 'payments-ledger-platform')
ON CONFLICT (id) DO NOTHING;
