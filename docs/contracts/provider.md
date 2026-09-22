# Provider 端口契约

版本：PR05 · 日期：2026-09-22 · 状态：端口、持久 Fake 与 Stripe SDK 适配已实施；真实 Checkout 基础契约已通过，其余支付 fixture 与 webhook 版本仍待验证

关联：[PRD §2/§5/§14](../product/payment-platform-prd.md) · [AccountScope](account-scope.md) · [操作状态机](../state-machines/provider-operation.md) · [版本与能力矩阵](../spec/version-and-capability-matrix.md) · [ADR-0003 未知结果](../adr/0003-provider-unknown-result.md)

端口由 PR04 定义并实现 `FakeStripeProvider`；PR05 实现真实 `Stripe*Adapter`。**Stripe SDK 类型只允许出现在 adapter 内部**，端口签名使用本项目自有 DTO。业务层不得 import 任何 `com.stripe.*`。

## 1. 设计原则

1. 端口按 **Checkout 实际语义**定义，不提供本期业务未使用的泛化 `confirmPayment` 假接口。
2. 端口返回**事实与证据**，不返回本地业务状态。adapter 不得修改 `payment`、不得直接入账（PR05 约束）。
3. 所有调用在**数据库事务外**执行，且**不持有**本地业务锁。
4. 每次调用返回 `ProviderCallResult`，其中 `classification` 是归一化错误分类（§5），业务层只依据 `classification` 决策，不解析 provider 原始错误串。
5. 「查询为空」**不等于**「不存在」。短期查询为空是暂态（`TRANSIENT_EMPTY`），不能推断远端未执行。

## 2. 端口清单与归属

| 端口 | 方法 | 首次使用 PR | 幂等性 |
|---|---|---|---|
| `CheckoutPort` | `createSession` / `querySession` / `expireSession` | PR05、PR06 | create 需幂等键；query/expire 天然幂等 |
| `PaymentIntentPort` | `queryPaymentIntent` | PR05、PR07 | 只读 |
| `ChargePort` | `queryCharge` / `listChargeAttempts` | PR05、PR08 | 只读 |
| `RefundPort` | `createRefund` / `queryRefund` | PR05、PR11 | create 需幂等键 |
| `BalanceTransactionPort` | `listBalanceTransactions` / `getBalanceTransaction` | PR05、PR10 | 只读、分页 |
| `DisputePort` | `listDisputes` / `getDispute` | PR05、PR13 | 只读 |
| `PayoutPort` | `listPayouts` / `getPayout` | PR05、PR14 | 只读 |
| `BalancePort` | `getBalance` | PR05、PR15 | 只读快照 |
| `ReportPort` | `requestReport` / `fetchReport` | PR05、PR15 | 只读；**能力可能不可用** |
| `WebhookVerifierPort` | `verify(rawBytes, signatureHeader, secret)` | PR05、PR08 | 纯函数 |

`ReportPort` 的可用性取决于测试账户能力，**PR00 不假设可用**。不可用时 PR05 标 `BLOCKED`，PR15 改用受控静止区间快照并把运行标为 `INCOMPLETE`（见 §7）。

## 3. 端口签名与 DTO

### 3.1 通用信封

```java
record ProviderCallResult<T>(
    ProviderCallClassification classification,  // §5
    T payload,                                  // SUCCESS/AUTH_REQUIRED 可携带事实；其他分类为 null
    ProviderEvidence evidence                   // 始终存在，即使失败
) {}

record ProviderEvidence(
    String providerRequestId,     // Stripe request id，用于取证与支持工单
    String idempotencyKeyUsed,    // 本次实际发出的键；null 表示只读调用
    Instant requestedAt,
    Instant respondedAt,          // 超时为 null
    Integer httpStatus,           // 无响应为 null
    String providerErrorCode,     // 原样保留，不做业务解释
    String rawDigest,             // 脱敏后响应摘要（SHA-256），不存完整卡数据/secret
    String apiVersion             // 实际生效的 Stripe API 版本
) {}
```

`ProviderEvidence` **在超时与失败时同样必须持久化**——这是 UNKNOWN 恢复的取证基础（AC-V1-08、F-05）。

### 3.2 Checkout

```java
record CreateCheckoutSessionCommand(
    ProviderScope scope,
    String operationIdempotencyKey,   // 持久化的操作级键，非请求级
    long amountMinor, String currency,
    String successUrl, String cancelUrl,
    Map<String,String> sessionMetadata,        // 顶层 metadata
    Map<String,String> paymentIntentMetadata,  // 必须经 payment_intent_data.metadata 显式设置
    Instant expiresAt                        // 整秒；与操作键一起持久化，重试不重新计算
) {}

record CheckoutSessionFact(
    String sessionId,
    CheckoutSessionStatus status,     // OPEN / COMPLETE / EXPIRED
    String hostedUrl,                 // 可能为 null（已 complete/expired）
    String paymentIntentId,           // 创建时可能为 null，后续查询补齐
    String paymentStatus,             // provider 原值：unpaid / paid / no_payment_required
    Instant expiresAt,
    Map<String,String> metadata
) {}
```

**metadata 不自动传播**：需要出现在 PI 上的字段必须放进 `paymentIntentMetadata`，PR05 用真实契约核验，不假设顶层 metadata 自动继承（PRD FR-PAY-01）。

`expireSession(scope, sessionId)`：**仅当 `status=OPEN` 时**按官方接口过期。其他状态返回 `DEFINITIVE_REJECT` 或当前事实，由业务层重新取证——`expireSession` **不等于**取消任意 PI（PRD FR-PAY-03）。

### 3.3 PaymentIntent / Charge

```java
record PaymentIntentFact(
    String paymentIntentId, String status,   // provider 原值
    long amountMinor, Long amountReceivedMinor, String currency,
    List<String> chargeIds,                  // 可能多次尝试
    String latestChargeId, String nextActionType,
    Map<String,String> metadata, Instant createdAt
) {}

record ChargeFact(
    String chargeId, String paymentIntentId, String status,
    boolean captured, long amountMinor, Long amountCapturedMinor, String currency,
    String balanceTransactionId,             // 可能为 null → financialStatus=PENDING_EVIDENCE
    String failureCode, String outcomeType, Instant createdAt
) {}
```

一个 PI 可关联**多次** Charge 尝试；`listChargeAttempts` 返回全部。业务层不得假设 1:1（PRD §2、AC-V1-06）。

### 3.4 Refund

```java
record CreateRefundCommand(
    ProviderScope scope, String operationIdempotencyKey,
    String chargeId, String paymentIntentId,   // 二选一，按冻结契约定
    long amountMinor, String reason
) {}

record RefundFact(
    String refundId, String chargeId, String status,   // provider 原值
    long amountMinor, String currency,
    String balanceTransactionId,        // 退款 BT，可能晚到
    String failureReason,
    String failureBalanceTransactionId, // 失败返还 BT
    Instant createdAt
) {}
```

退款状态与退款 BT **可能不同步**，业务层双维度呈现（FR-REF-02、AC-V1-21）。

### 3.5 Balance Transaction（取证核心）

```java
record ListBalanceTransactionsQuery(
    ProviderScope scope,
    Instant createdGte, Instant createdLt,   // 由业务时区生成的明确 UTC 区间
    String startingAfterCursor, int limit,
    String payoutId                          // 仅自动 payout 归属查询时使用
) {}

record BalanceTransactionFact(
    String btId, String type,
    long amountMinor, long feeMinor, long netMinor,  // net = amount - fee
    String currency, String balanceDomain,           // PAYMENTS / ISSUING / ...
    String sourceObjectId, String sourceObjectType,
    String status,                                   // pending / available
    Instant createdAt, Instant availableOn,
    List<FeeDetail> feeDetails, String rawDigest
) {}

record BalanceTransactionPage(
    List<BalanceTransactionFact> items,
    boolean hasMore, String nextCursor
) {}
```

**分页完整性是 I-05 的前提**：`hasMore=true` 时窗口**不得**标记 `COMPLETE`；游标必须持久化，崩溃后可安全继续（FR-REC-01、AC-V1-16）。

按 payout 查询所含交易**仅适用于自动 payout**；手动 payout 不得用 FIFO 猜测归属（PRD FR-PO-01、S8）。

### 3.6 Dispute / Payout / Balance / Report

```java
record DisputeFact(String disputeId, String chargeId, String status, String reason,
                   long amountMinor, String currency, Instant evidenceDueBy,
                   List<String> balanceTransactionIds);

record PayoutFact(String payoutId, String status, long amountMinor, String currency,
                  String balanceTransactionId,          // 扣减 BT
                  String failureBalanceTransactionId,   // 失败返还 BT
                  boolean automatic, Instant arrivalDate, String failureCode);

record BalanceSnapshot(String currency, String balanceDomain,
                       long availableMinor, long pendingMinor,
                       Instant capturedAt);   // 当前快照，禁止冒充历史期末
```

`getBalance` 返回的是**当前**余额。它**不能**与昨日账本截止点直接比较（PRD §11、AC-V1-29）。历史期末优先用 `ReportPort`；不可用时使用受控静止区间快照并保存"窗口内无其他资金变动"的证据，否则运行为 `INCOMPLETE`。

## 4. 幂等窗口语义

| 项 | 约定 | 依据 |
|---|---|---|
| 键的作用域 | 每个**资金写操作**一个持久化 `operation_key`；create/confirm/refund **不复用** | PRD FR-PAY-01 |
| 键的存储 | PostgreSQL `provider_operation.operation_key`，**不依赖 Redis** | I-02、AC-V1-04 |
| 提供方缓存 | Stripe 会缓存结果（**包含部分错误结果**），且键不永久保留 | [S2](https://docs.stripe.com/api/idempotent_requests) |
| 本项目保留窗口 | `provider_idempotency_retention`，**PR05 实测前不得写入具体小时数** | 待验证 |
| Fake 行为 | PR04 的 Fake **必须**实现有限窗口；窗口届满同键可能被当作新请求处理 | AC-V1-09 |
| 窗口内重试 | 允许用**原键**重发，用于恢复 UNKNOWN | AC-V1-08 |
| 窗口外且无外部 ID | **保持 UNKNOWN + 升级告警**；禁止换键、禁止盲目重发 | AC-V1-09、F-05 |

决策树（业务层，PR07/PR11 实现）：

```text
UNKNOWN 结果
  ├─ 有外部对象 ID？ ── 是 ──> 按 ID 查询取证 ──> 归并事实
  │                     否
  ├─ 有持久关联（metadata/order_key）可反查？ ── 是 ──> 外部枚举取证
  │                     否
  ├─ 仍在已验证保留窗口内？ ── 是 ──> 用原键安全重试
  │                     否
  └─ 无法排除已受理 ──> 保持 UNKNOWN，创建 ESCALATED 调查任务，告警
                        ✗ 不换键  ✗ 不重发  ✗ 不标记为失败
```

"等待够久"**不是**安全重试的证明（验收 §4.1）。

## 5. 错误归一化分类

`classification` 是端口对外的唯一错误语义。业务层禁止解析 provider 原始文案。

| 分类 | 含义 | 是否有远端效果 | 业务处理 | resolution |
|---|---|---|---|---|
| `SUCCESS` | 明确成功 | 有 | 记录事实 | `KNOWN` |
| `DEFINITIVE_REJECT` | 提供方明确拒绝（卡拒付、参数非法、状态不允许） | **无** | 结束本次尝试；卡拒付只终结 attempt，不终结 Payment | `KNOWN` |
| `AUTH_REQUIRED` | 需要客户认证动作 | 部分（PI 已创建） | 返回 `nextAction` | `KNOWN` |
| `NOT_FOUND` | 对象确定不存在 | 无 | 结合本地证据判断；不可单独据此断言未执行 | 视上下文 |
| `TRANSIENT_EMPTY` | 查询暂时为空（最终一致延迟） | **未知** | 继续 L1 退避查询，**不得**推断未执行 | `UNKNOWN` |
| `RETRYABLE_TRANSIENT` | 5xx、连接重置、限流后可重试 | **未知** | 退避重试（原键） | `UNKNOWN` |
| `RATE_LIMITED` | 429 | 无 | 账户级限流退避 | `UNKNOWN` |
| `TIMEOUT_UNKNOWN` | 请求已发出但无响应 | **可能有** | 进入 UNKNOWN 决策树 | `UNKNOWN` |
| `IDEMPOTENCY_CONFLICT` | 同键不同参数被提供方拒绝 | 无本次效果 | 视为严重缺陷，冻结并调查 | `CONFLICT` |
| `CAPABILITY_UNAVAILABLE` | 账户/版本不支持该能力 | 无 | 标 `BLOCKED`，走 fixture 替代 | `KNOWN` |
| `CONTRACT_MISMATCH` | 返回结构与冻结契约不符 | 未知 | 停止自动处理，保留证据升级 | `CONFLICT` |

**`TIMEOUT_UNKNOWN` 与 `DEFINITIVE_REJECT` 的区分是本项目的核心资金安全边界**：把超时归类为失败会导致重复扣款或错误退款（F-05）。

## 6. Fake 行为要求（PR04）

`FakeStripeProvider` 必须可编排、可复现、可持久：

| 能力 | 要求 |
|---|---|
| 故障脚本 | 发送前失败、受理后响应丢失、短期查询为空、暂态错误、拒绝、需认证、多 Charge attempt、session 过期/取消与成功竞争 |
| 事件控制 | Webhook 丢失/重复/乱序；BT 分页/晚到/pending→available；退款 pending/failed 与返还；争议与 payout fixture |
| 幂等窗口 | **有限**保留窗口 + 可控 `Clock`；窗口届满按新请求处理 |
| 效果计数 | `requestCount`、`acceptedCount`、`sessionCreatedCount`、`paymentEffectCount`、`refundEffectCount` **独立持久化** |
| 原子性 | 与本地数据库**无**共享事务；支持"远端效果已发生、本地未回写"的中断注入 |
| 禁止 | 让 Fake 读取本地业务状态自动修正远端事实 |

**`sessionCreatedCount` 不计入 `paymentEffectCount`**：创建 Checkout 会话不是扣款（AC-V1-05）。"没有重复扣款"必须用 Fake 侧独立持久的资金效果日志证明，**不能**用本地 `payment` 行数自证（验收 §6）。

## 7. 真实契约验证归属（PR05）

| 验证项 | PR05 期望 | 无法验证时 |
|---|---|---|
| Checkout create/query/expire + hostedUrl | 实测记录 | `BLOCKED` |
| PI ↔ Charge 关联、多 attempt、自动 capture | 实测记录 | `BLOCKED` |
| metadata 显式放置与传播 | 实测核验，**不假设自动继承** | `BLOCKED` |
| Refund create/query | 实测记录 | `BLOCKED` |
| BT 分页 + amount/fee/net/source/status/available_on | 实测记录 | `BLOCKED` |
| Dispute / Payout / Balance / Report 读取 | 账户能力允许时实测 | `BLOCKED` 或 `fixture-only`，**相关 AC 不得 PASS** |
| 幂等键保留窗口 | 实测或引用官方契约 | 保持"不可依赖永久去重"设计 |
| Webhook 原始字节验签 | 真实接收 fixture + 本地签名 fixture **分别标注来源** | 不得把本地伪造签名说成 Stripe 实测 |

PR05 的报告必须保存：冻结版本、运行时间、脱敏对象引用、**实测/fixture 矩阵**。`fixture-only` 不能冒充真实联调（PRD §18、路线图 §4.1）。

## 8. PR04 实现与使用

端口与 DTO 位于 `com.paymentsledger.provider`，Fake 位于 `provider.fake`。有限窗口、持久故障脚本、独立效果计数及 fixture 使用方式见[模拟器运行手册](../runbook/provider-simulator.md)。API 版本标记为 `FAKE-PR04-v1`，来源为 `PROVIDER_SIMULATOR`；不代表真实 Stripe 契约已通过。

## 9. PR05 适配边界

`provider.adapter.stripe` 独占 Stripe SDK 依赖。每组适配器绑定一个可信本地 scope 和一个直接 Stripe 测试账户；首次 HTTP 调用先用 `/v1/account` 核对凭据所属账户，不接受请求 metadata 选择账户，不启用 Connect 路由。显式开关、测试凭据、endpoint API 版本均为必需配置。

Checkout 的 `expiresAt` 改为绝对整秒时间（PR04 的相对 Duration 在延迟重试时会改变请求参数）。PR06 必须与 operation key 一起持久化。Fake 同步采用绝对时间；新建时校验 30 分钟至 24 小时范围，保留键重放先于时间检查。旧 Fake 状态中的相对参数不会自动迁移，新命令同键返回参数冲突；本地开发应使用新的模拟器目录，不覆盖原证据。

所有 SDK 调用关闭自动重试。Checkout/refund 使用调用者操作键；expire 使用 scope+session 派生的固定键并记录实际发送值，Stripe 的 POST 默认随机键不会被误记为 null。SDK API 请求固定 `2026-07-29.dahlia`，本地 SDK `33.3.0`。真实基础契约已验证；账户既有测试 endpoint 使用 2026-06-24.dahlia，未绑定到本项目，完整账户能力与 endpoint 接收契约仍未冻结。

`idempotency_key_in_use`/409、424、5xx 为暂态未知；只有同键不同参数的 idempotency error 才是 CONFLICT。连接无响应为 TIMEOUT_UNKNOWN（无 httpStatus/respondedAt）；明确 resource_missing 的 404 为 NOT_FOUND；空 Charge 枚举为 TRANSIENT_EMPTY，空财务窗口列表是成功观察，均不能推导资金操作未发生。`authentication_required` 可返回 AUTH_REQUIRED 且无 payload，调用者仍须按已有对象关联取证。

Charge 尝试显式逐页读取；重复页/关联错误/上限截断不能返回成功。财务流水读取 `balance_type`，缺失或不识别时 CONTRACT_MISMATCH，不猜 PAYMENTS。手动 payout 归属返回 CAPABILITY_UNAVAILABLE。Balance 仅支持当前 PAYMENTS 域；未验证域与历史报告均显式不可用。没有下载报告 CSV 或用空 balances 冒充历史期末证据。

证据摘要只含 scope、账户引用、请求引用、事实字段摘要；metadata、托管 URL、描述在 hash 前移除。复合读取的 providerRequestId 是最后一次调用，rawDigest 覆盖调用序列；调用者仍负责持久化输入 scope、对象引用与结果。SDK 原始错误文案/响应不返回、不记录。Spring guard 拒绝活跃事务/事务同步；任意自定义业务锁仍由调用者遵守“调用前释放”的约束。

来源：Stripe 官方 [幂等请求](https://docs.stripe.com/api/idempotent_requests)、[错误类型](https://docs.stripe.com/api/errors)、[会话过期](https://docs.stripe.com/api/checkout/sessions/expire)、[BT 余额类型](https://docs.stripe.com/changelog/basil/2025-05-28/add-balance-type-to-balance-transactions)、[Report Runs](https://docs.stripe.com/api/reporting/report_run)。幂等文档说键可在至少 24 小时后清理；这不是账户实测保留期，尚未启用任何自动恢复窗口配置。
