# AccountScope 契约：可信绑定、唯一键与外键规则

版本：PR00 · 日期：2026-09-10 · 状态：冻结设计，未实施

关联：[PRD §14.2](../product/payment-platform-prd.md) · [需求映射](../spec/requirements-map.md) · [OpenAPI](openapi.yaml) · [Provider 端口](provider.md) · [账本科目](../ledger/accounts.md) · [ADR-0006 认证边界](../adr/0006-authentication-and-tenant-boundary.md)

本文件定义"一笔钱属于谁、在哪个环境、哪个币种、哪个余额域"的判定规则。所有实现（PR02 建表、PR03 账本约束、PR06 起业务表）必须以本文件为准。**范围不是可选装饰字段，是资金归属的一部分。**

## 1. 范围分层

| 范围 | 组成 | 来源 | 用途 |
|---|---|---|---|
| `TenantScope` | `organization_id` + `environment_id` | **由认证凭据解析**，不由客户端指定 | 一切业务资源的最外层归属 |
| `ApplicationScope` | `TenantScope` + `application_id` | 认证凭据 | 业务订单唯一键、API 权限、通知目标 |
| `ProviderScope` | `TenantScope` + `provider_account_id` + `livemode` | 授权配置解析 | 外部对象身份、Webhook 去重、BT 规范键 |
| `MoneyScope` | `TenantScope` + `currency` | 业务订单/受控价格 | 单张 Journal 内一致，禁止跨币种相加 |
| `PSPControlScope` | `ProviderScope` + `currency` + `balance_domain` | BT 事实属性 | PSP 控制余额、I-05 核对单元 |
| `AccountScope` | `TenantScope` + `currency` + `account_purpose` + 可空 `provider_account_id` + `source_domain` | 账本科目登记 | 账本科目唯一性与 I-07 隔离 |

`AccountScope` 是账本侧的范围，**不等于** `PSPControlScope`。只有 PSP 类科目才带 `provider_account_id`；银行与内部分润科目不带（见 §6）。

## 2. 可信绑定：凭据 → TenantContext

### 2.1 业务应用（Business API）

```text
Authorization: Bearer <application_secret>
        │
        ├─ 查 application_credential（只存 Argon2id/bcrypt 摘要，不存明文）
        ├─ 校验 status=ACTIVE、not_before <= now < expires_at
        └─ 解析出不可覆盖的 TenantContext：
             organization_id
             environment_id
             application_id
             allowed_provider_account_id      （单账户，本期固定一个）
             allowed_currencies[]             （本期实际运行一个）
             granted_scopes[]                 （payments:write / payments:read / refunds:write / refunds:read）
             livemode                          （由 environment_config 决定）
```

**强制规则**

1. 请求体、查询参数、Header 中出现 `organization_id`、`environment_id`、`provider_account_id`、`livemode`、`account_id` 等归属字段时：与 TenantContext 不一致 → `403 FORBIDDEN`；一致 → 忽略该字段（**不作为**授权依据）。任何情况下都不得把请求内容当认证来源。
2. 缺失、格式错误、过期或吊销的凭据 → `401 UNAUTHORIZED`，且**不泄露**资源是否存在。
3. 跨组织读取（路径参数指向他组织资源）→ `404 NOT_FOUND`，避免通过 403/404 差异枚举他人资源 ID。跨组织**写入**在数据库层还有 FK/CHECK 兜底。
4. `environment_config.livemode = true` 的凭据在本期**启动时即拒绝**（`X-SEC`，AC-V1-02）。这是进程级检查，不是请求级检查。

### 2.2 运营身份（Ops API）

运营身份与业务应用凭据**分属不同表、不同认证方式、不同 scope 命名空间**，不得互相复用：

| 维度 | 业务应用 | 运营身份 |
|---|---|---|
| 主体 | `application` | `ops_identity`（人或服务账号） |
| 凭据 | `application_credential` | `ops_credential` / OIDC bearer |
| scope 前缀 | `payments:`、`refunds:` | `ops:` |
| 可做的事 | 创建/查询自身应用的支付与退款 | 跨应用只读、对账回补、导入、异常动作、调整申请与审批 |
| 不可做的事 | 任何 `/ops/**` | 直接改旧凭证、直接改余额（**接口不存在**） |

运营角色（PR16 实施，PR00 冻结语义）：

| 角色 | 只读 | 回补/导入 | 异常动作/重试 | 调整申请 | 调整审批 |
|---|---|---|---|---|---|
| `OPS_VIEWER` | ✅ | ❌ | ❌ | ❌ | ❌ |
| `OPS_OPERATOR` | ✅ | ✅ | ✅ | ✅ | ❌ |
| `OPS_REVIEWER` | ✅ | ❌ | ❌ | ❌ | ✅ |
| `OPS_ADMIN` | ✅ | ✅ | ✅ | ✅ | ❌（**不能**自审，见下） |
| `PARTNER_VIEWER` | 仅自身 `partner_id` 的计提/撤回明细 | ❌ | ❌ | ❌ | ❌ |

**审批分离是硬约束**：`adjustment_request.created_by <> adjustment_approval.approved_by`，由数据库 CHECK + 应用双重强制。`OPS_ADMIN` 拥有全部写权限**不代表**可以审批自己的申请；审批必须由持 `OPS_REVIEWER` 的另一主体完成。

### 2.3 Webhook 入口

无 Bearer 认证。身份来自 **`Stripe-Signature` 对原始请求字节的验签**（PR08）：

1. 按 endpoint 配置定位 `webhook_endpoint_secret`；一个 endpoint 绑定唯一 `ProviderScope`。
2. 验签使用**原始字节**，不得先反序列化再重新序列化。
3. 验签通过后，比对事件负载中的 `account` / `livemode` 与该 endpoint 绑定的 `ProviderScope`；不匹配 → 拒绝并记录，不进入业务管道。
4. 验签失败 → `400`，**无任何**业务或财务副作用（AC-V1-11、F-04）。

## 3. 实体归属与唯一键

`org` = `organization_id`，`env` = `environment_id`，`pa` = `provider_account_id`。所有表都带 `created_at`、`updated_at`（`timestamptz`）与乐观锁 `version`。

### 3.1 配置与身份（PR02）

| 表 | 主键 | 唯一键 | 关键 FK / CHECK |
|---|---|---|---|
| `organization` | `id` | `(code)` | — |
| `environment_config` | `id` | `(org, code)` | `livemode boolean NOT NULL`；本期 CHECK 启动校验拒绝 live |
| `application` | `id` | `(org, env, code)` | FK→`environment_config(org, id)` 复合 |
| `application_credential` | `id` | `(application_id, key_id)` | FK→`application`；只存摘要 |
| `provider_account` | `id` | `(org, env, provider, provider_account_ref)` | `livemode` 必须等于所属 `environment_config.livemode`（复合 FK 强制） |
| `currency_config` | `id` | `(code)` | `minor_unit_exponent smallint`（USD=2、JPY=0）；CHECK `0<=exponent<=4` |
| `org_currency_enabled` | `id` | `(org, env, currency)` | FK→`currency_config` |
| `ops_identity` / `ops_credential` | `id` | `(org, subject)` / `(ops_identity_id, key_id)` | 与 `application` 完全分离 |
| `price_fixture` | `id` | `(org, env, product_ref, price_version)` | 权威金额来源，客户端不可覆盖 |
| `share_policy_fixture` | `id` | `(org, env, product_ref, policy_version)` | 比例基点合计 CHECK `<=10000` |

**`environment ↔ livemode` 一致性**是复合外键，不是应用检查：

```sql
-- provider_account 必须与其 environment 的 livemode 一致
ALTER TABLE provider_account
  ADD CONSTRAINT fk_pa_env_livemode
  FOREIGN KEY (organization_id, environment_id, livemode)
  REFERENCES environment_config (organization_id, id, livemode);
```

同一模式用于 `payment`、`refund`、`balance_transaction`、`payout` 等所有带 `livemode` 的表：**改 livemode 绕过冲突检查在数据库层不可能成功**。

### 3.2 业务资源（PR06、PR11）

| 表 | 唯一键 | 说明 |
|---|---|---|
| `business_order` | `(org, env, application_id, order_key)` | 业务唯一性；**换 API 幂等键也不能为同一订单建第二笔支付** |
| `payment` | `(business_order_id)` | 一订单一支付（本期），保证上一条 |
| `api_idempotency_record` | `(org, env, application_id, operation_type, idempotency_key)` | 与业务唯一键**是两项独立约束**；`operation_type` 区分 `PAYMENT_CREATE` / `REFUND_CREATE` / `PAYMENT_CANCEL` |
| `payment_attempt` | `(payment_id, provider_object_type, provider_object_id)` | 多 attempt 不等于多次成功 capture |
| `provider_operation` | `(org, pa, livemode, operation_type, operation_key)` | 操作级持久幂等键；create/confirm/refund **不共用** |
| `refund_control` | `(payment_id)` | 承载 C/R/H 与 `control_state` |
| `refund` | `(refund_control_id, client_refund_key)` | 本地退款唯一性 |

### 3.3 外部对象与财务事实（PR08、PR10、PR13、PR14）

| 表 | 唯一键 | 说明 |
|---|---|---|
| `webhook_inbox` | `(org, pa, livemode, provider_event_id)` | 传输层去重（第一道防线） |
| `provider_object_ref` | `(org, pa, livemode, object_type, object_id)` | 外部对象身份 |
| `balance_transaction` | `(org, pa, livemode, bt_id)` | **规范外部财务键**。`currency` 是必须校验的事实属性，**不是**去重维度 |
| `financial_event` | `(org, env, event_key)` | 规范化财务事件；语义层去重（第二道防线） |
| `journal` | `(financial_event_id)` | 事件与 Journal 一对一（I-02） |
| `dispute` | `(org, pa, livemode, dispute_id)` | 一个 Charge 可有多项争议 |
| `payout` | `(org, pa, livemode, payout_id)` | — |
| `bank_statement_line` | `(bank_statement_id, line_no)` + `(org, env, bank_ref, value_date, amount, seq)` | 一行不能被重复消耗 |
| `payout_bank_match` | `(payout_id, bank_statement_line_id)` | 累计分配 ≤ 已转出本金（I-06） |

**BT 去重键的关键约定**（PRD §4.2、验收 §4.2）：

- 规范键 = `(organization_id, provider_account_id, livemode, bt_id)`。
- `currency`、`amount`、`fee`、`net`、`source`、`balance_domain` 是**不可变事实属性**。同键不同事实 → `EVIDENCE_CONFLICT`，**不是** duplicate，也**不能**因此产生第二个事件键。
- 同一 `bt_id` 字符串出现在不同 `provider_account_id`/`livemode` 下 → 按 `ProviderScope` 独立识别，同时校验来源归属；**不能**因字符串相同串账。
- 一个外部资金来源**不能**注册到多个组织重复归属：`provider_account.provider_account_ref` 全局唯一（跨组织），防止"另注册一个组织再导一遍同一账户"。

## 4. 环境、livemode 与币种

| 概念 | 含义 | 谁决定 | 错误处理 |
|---|---|---|---|
| `environment_id` | 本地部署环境（如 `local`、`ci`、`demo`） | 认证凭据 | 缺失 → `401` |
| `livemode` | Stripe 侧真实资金标志 | `environment_config`，并与 `provider_account` 复合 FK 一致 | 本期 `true` → **启动拒绝** |
| `currency` | ISO 4217 三字母 | 受控价格 fixture / BT 事实 | 未启用 → `422 CURRENCY_NOT_ENABLED` |
| `minor_unit_exponent` | 最小单位换算 | `currency_config` | USD=2（10000 = USD 100.00）、JPY=0（10000 = JPY 10000） |

金额一律 `bigint` 最小单位整数，禁止浮点。跨币种相加在单张 Journal 内由 CHECK 拒绝（I-01/I-07）。

## 5. Balance domain（余额域）

`balance_domain` 是 PSP 侧资金所在的余额空间，**不是**可用性状态。

| 值 | 处理 | 是否进入 payments 账本 |
|---|---|---|
| `PAYMENTS` | 本期唯一入账域 | ✅ |
| `ISSUING` / `TREASURY` / `OTHER` | 保留隔离观察，进入未覆盖清单 | ❌ 严禁混入 |
| `UNKNOWN` | 隔离 + `UNSUPPORTED_BALANCE_DOMAIN` 差错 | ❌ |

`pending` → `available` 只改变**可用性维度**，`PSPControlScope` 的总额不变，**不生成第二次收款凭证**（AC-V1-30）。可用性建模为 `balance_transaction.available_on` + 投影上的可用性子维度，不是新的资金事件。

已核验属于 `PAYMENTS` 域、金额可验证、但业务类型未知的净变化 → 以稳定事件键记 `PSP_FUNDS ↔ SUSPENSE`，并开 `UNKNOWN_SOURCE` 差错等待审批重分类。**Suspense 不是业务归属完成。**

## 6. 演示银行科目与分润科目的来源归属

这是最容易被"给所有科目都塞一个 provider_account_id"糊弄过去的地方。规则如下：

| 科目类别 | `provider_account_id` | `source_domain` | 归属证据来源 |
|---|---|---|---|
| `PSP_FUNDS` | **必填** | `PROVIDER` | Stripe BT（`ProviderScope` + `bt_id`） |
| `BANK_PAYOUT_IN_TRANSIT` | **必填**（资金来自该 Stripe 账户） | `PROVIDER` | payout 扣减 BT |
| `BANK_CASH` | **必须为 NULL** | `SIMULATED_BANK` | `bank_statement_line`（fixture，明确标注 simulated） |
| `BANK_FEE_EXPENSE` | NULL | `SIMULATED_BANK` | 银行行费用字段 |
| `PARTNER_SHARE_EXPENSE` / `PARTNER_PAYABLE` | **必须为 NULL** | `INTERNAL` | 下单政策快照 + 内部业务事件键 |
| `SALES_REVENUE` / `SALES_RETURNS` | NULL | `INTERNAL` | 由 BT 事实驱动的内部收入确认 |
| `PSP_FEE_EXPENSE` | 必填 | `PROVIDER` | BT `fee` / `fee_details` |
| `DISPUTE_RECEIVABLE` / `DISPUTE_FEE_EXPENSE` / `DISPUTE_LOSS` | 应收与费用必填；`DISPUTE_LOSS` NULL | `PROVIDER` / `INTERNAL` | 争议 BT；损失为内部重分类 |
| `SUSPENSE` | 必填（隶属某 PSP 域时） | `PROVIDER` | 已验证净变化但业务类型未知 |
| `OPENING_EQUITY` | NULL | `INTERNAL` | 经核对的期初快照，**禁止**用于掩盖差异 |

对应约束：

```sql
-- 科目用途决定 provider_account_id 是否必须存在，不允许"字段可空"绕过隔离
ALTER TABLE ledger_account ADD CONSTRAINT ck_account_provider_binding CHECK (
  (source_domain = 'PROVIDER'       AND provider_account_id IS NOT NULL) OR
  (source_domain = 'SIMULATED_BANK' AND provider_account_id IS NULL)     OR
  (source_domain = 'INTERNAL'       AND provider_account_id IS NULL)
);
```

**结论**：银行科目和分润科目**不伪造** `provider_account_id`，但它们的 `organization_id`、`environment_id`、`currency`、`account_purpose`、`source_domain` 全部必填。"字段可空"不能成为跳过 I-07 隔离检查的借口——`source_domain` 承担了归属证据的分类职责。

`BANK_CASH` 在本期一律为 **simulated bank evidence**：页面、报告、README 和简历材料都必须如此标注，不得表述为真实银行直连或真实到账。

## 7. 科目唯一键与账本 FK

```sql
-- 一个 AccountScope 下同一用途只有一个科目
CREATE UNIQUE INDEX uq_ledger_account_scope ON ledger_account (
  organization_id, environment_id, currency, account_purpose,
  coalesce(provider_account_id, '00000000-0000-0000-0000-000000000000'::uuid),
  coalesce(balance_domain, 'NONE')
);

-- 分录必须与所属 Journal 同 org/env/currency（I-07 提交时强制）
ALTER TABLE journal_entry
  ADD CONSTRAINT fk_entry_journal_scope
  FOREIGN KEY (journal_id, organization_id, environment_id, currency)
  REFERENCES journal (id, organization_id, environment_id, currency);

-- 分录引用的科目必须落在同一 AccountScope
ALTER TABLE journal_entry
  ADD CONSTRAINT fk_entry_account_scope
  FOREIGN KEY (ledger_account_id, organization_id, environment_id, currency)
  REFERENCES ledger_account (id, organization_id, environment_id, currency);
```

这三条使"一张凭证跨组织/跨环境/跨币种"在提交时即失败，无需依赖 Java 校验（AC-V1-17、I-07）。

## 8. `provider_operation` 归属约束（PR00 冻结决策）

采用 **payment_id / refund_id 互斥外键**（详见 [ADR-0005](../adr/0005-provider-operation-association.md)）：

```sql
-- PR06 建表：payment_id 有 FK，refund_id 预留列（暂无 FK）
-- PR11 追加迁移：为 refund_id 加 FK
ALTER TABLE provider_operation ADD CONSTRAINT ck_operation_target_exclusive
  CHECK (num_nonnulls(payment_id, refund_id) = 1);

ALTER TABLE provider_operation ADD CONSTRAINT ck_operation_target_matches_type CHECK (
  (operation_target = 'PAYMENT' AND payment_id IS NOT NULL) OR
  (operation_target = 'REFUND'  AND refund_id  IS NOT NULL)
);
```

**禁止**使用无人校验的任意字符串 `resource_id` 替代归属约束。同一套 `provider_operation` + `recovery_task` + 租约覆盖支付与退款两种目标（AC-V1-09、10、20）。

## 9. 反面清单

以下做法一律视为契约违规：

1. 从请求体读取 `organization_id` 并据此查询或写入。
2. 科目表把 `provider_account_id` 设为可空后，对银行/分润科目不再填写任何归属字段。
3. 把 `currency` 加进 BT 去重键，使"同 BT 改币种"变成一条新记录而非冲突。
4. 用 Redis 中的幂等缓存作为业务唯一性依据（缓存过期即可重复创建）。
5. 用 `application_id` 冒充 Stripe 侧的分账账户，制造"多商户"假象。
6. Webhook 处理器先 `JSON.parse` 再验签。
7. 运营写接口允许申请人自审，或提供直接修改 `journal` / `balance_projection` 的入口。
