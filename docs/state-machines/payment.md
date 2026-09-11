# 状态机：Payment

版本：PR00 · 状态：冻结设计，未实施

关联：[PRD §13](../product/payment-platform-prd.md) · [OpenAPI](../contracts/openapi.yaml) · [操作状态机](provider-operation.md) · [退款状态机](refund.md) · [需求映射](../spec/requirements-map.md)

## 1. 四个维度必须分开存储

**同一行记录里有四个互相正交的字段，任何一个都不能覆盖另一个。**

| 维度 | 字段 | 取值 | 谁写入 |
|---|---|---|---|
| 本地业务状态 | `payment_status` | `CREATED` / `AWAITING_CUSTOMER` / `REQUIRES_ACTION` / `PROCESSING` / `SUCCEEDED` / `CANCELED` | 本系统，依据已验证证据 |
| 提供方状态 | `provider_status` | Stripe 原值字符串，**原样保留** | 查询或已验签事件 |
| 结果确定性 | `resolution_status` | `KNOWN` / `UNKNOWN` / `CONFLICT` | 操作执行与恢复流程 |
| 账务确认度 | `financial_status` | `NOT_APPLICABLE` / `PENDING_EVIDENCE` / `PARTIALLY_POSTED` / `POSTED` / `CONFLICT` | 仅由入账管道（PR10 起） |

常见错误写法与后果：

- 把 `UNKNOWN` 写进 `payment_status` → 丢失最后已知的 provider 事实，恢复时无锚点。
- 用 `provider_status='succeeded'` 直接推出 `financial_status='POSTED'` → 在没有 BT 的情况下虚构手续费与银行资产（违反 PRD §4.2）。
- 卡被拒绝就把 `payment_status` 置为终态失败 → PI 仍允许换支付方式重试时，整个订单被错误终结（违反 FR-PAY-02）。

本模型**没有** `FAILED` 终态。一次尝试失败记录在 `payment_attempt` 上；Payment 本身只有 `CANCELED` 这一个非成功终态，且必须有"未发生资金事实"的证据支撑。

## 2. 状态含义

| 状态 | 含义 | 是否终态 |
|---|---|---|
| `CREATED` | 本地订单/支付/操作/执行义务已提交，尚未（或不确定是否）调用 Stripe | 否 |
| `AWAITING_CUSTOMER` | 托管 Checkout 会话已创建，客户尚未完成 | 否 |
| `REQUIRES_ACTION` | 需要客户认证（3DS 等），已给出托管入口 | 否 |
| `PROCESSING` | 已提交且提供方处理中，或结果待确认 | 否 |
| `SUCCEEDED` | 已确认成功 capture | **是**（但可后接退款/争议） |
| `CANCELED` | 已确认未发生且不会发生资金事实 | **是** |

`SUCCEEDED` 是终态的含义是：**成功 capture 这个事实不可被抹掉**。后续退款、争议扣款都不会把它改回其它状态——那些是独立对象上的新事实。

## 3. 允许的转换

| # | From | To | 触发 | 守卫条件 |
|---|---|---|---|---|
| T1 | — | `CREATED` | `POST /v1/payments` 事务提交 | 订单/幂等/操作/执行义务同事务落库 |
| T2 | `CREATED` | `AWAITING_CUSTOMER` | Checkout 会话创建成功 | 拿到 `sessionId` 与 hostedUrl |
| T3 | `CREATED` | `PROCESSING` | 调用结果 `TIMEOUT_UNKNOWN` | 同时置 `resolution=UNKNOWN` |
| T4 | `AWAITING_CUSTOMER` | `REQUIRES_ACTION` | PI 需要客户认证 | 有 `next_action` |
| T5 | `AWAITING_CUSTOMER` | `PROCESSING` | 会话 complete / PI processing | 已验签事件或权威查询 |
| T6 | `REQUIRES_ACTION` | `PROCESSING` | 客户完成认证 | 同上 |
| T7 | `PROCESSING` | `SUCCEEDED` | Charge 已 captured | **权威证据**：查询结果或已验签事件 |
| T8 | `AWAITING_CUSTOMER` | `SUCCEEDED` | 快速完成（事件先于中间态到达） | 同 T7；允许跳过中间态 |
| T9 | `AWAITING_CUSTOMER` | `CANCELED` | 会话 `OPEN` 时被 expire | **且**无任何已受理资金事实 |
| T10 | `CREATED` | `CANCELED` | 确证请求从未发出 | 只有可证明未发送的路径 |
| T11 | `PROCESSING` | `AWAITING_CUSTOMER` | 查询显示会话仍 open、无资金事实 | 需权威查询；**不接受**仅凭超时回退 |

T11 是**允许的回退**，因为 `payment_status` 反映的是当前已知事实，不是单调进度条。PRD FR-PAY-03 明确要求"不预设所有提供方对象状态只单向变化"。

## 4. 被拒绝的转换

| # | 被拒绝的转换 | 原因 | 系统行为 |
|---|---|---|---|
| R1 | `SUCCEEDED` → 任意其它状态 | 成功 capture 事实不可抹掉 | 拒绝写入；记 `STATUS_CONFLICT` 差错 |
| R2 | `CANCELED` → `SUCCEEDED`（无新证据） | 终态需新权威证据才能被推翻 | 拒绝；若确有资金事实则升为 `CONFLICT` 并人工调查 |
| R3 | 任意 → `SUCCEEDED`，仅凭客户回跳/HTTP 200 | 回跳不是资金事实 | 拒绝（PRD §4.1） |
| R4 | 任意 → `CANCELED`，仅凭本地超时 | 超时不能推断未付款 | 拒绝；保持 `UNKNOWN` |
| R5 | 任意 → `CANCELED`，仅凭客户点击取消 | 取消意图 ≠ 未付款 | 先持久化意图，取证后再决定 |
| R6 | 用较早到达的失败 attempt 覆盖已成功 capture | 乱序消息 | 拒绝；按证据优先级归并（§6） |
| R7 | `financial_status` 由 Webhook/Importer 直接置 `POSTED` | 只有入账管道可写 | 拒绝；投影只由 LedgerPostingService 更新 |

R1 与 R6 是 AC-V1-06/07/13 的核心断言：**成功 capture 不被较早失败 attempt 覆盖**。

## 5. 幂等重复转换

以下重复到达必须是**无副作用的幂等操作**，返回既有结果而不是报错：

| 场景 | 期望行为 |
|---|---|
| 同一 `eventId` 重投 | Inbox 传输层去重命中，返回 200，无二次业务效果 |
| 不同 `eventId` 指向同一财务事实 | 规范事件语义层去重，只有一个 Journal（I-02） |
| 已是 `SUCCEEDED` 再收到 `charge.succeeded` | 条件更新命中同值，**不**新增凭证、**不**重发通知 |
| 同一 Checkout 会话被重复查询 | 更新 `last_provider_confirmed_at`，状态不变 |
| 重复调用 `POST .../cancel` | 返回当前支付事实；不重复发送 expire 请求 |
| 重复创建同 `Idempotency-Key` 同内容 | 返回原资源（HTTP 200 + `Idempotent-Replay: true`） |

条件更新一律写成 `UPDATE ... WHERE id = ? AND version = ? AND payment_status IN (合法前驱)`，靠**受影响行数**判断是否命中，不靠先读后写。

## 6. 证据优先级

多个来源对同一事实给出不同说法时，按以下顺序采信（高 → 低）：

| 级别 | 来源 | 说明 |
|---|---|---|
| 1 | **BT（Balance Transaction）** | 资金是否真的移动的最终依据；只影响 `financial_status` |
| 2 | **权威查询**（`queryPaymentIntent` / `queryCharge`） | 主动拉取的当前事实，带 `fetched_at` |
| 3 | **已验签 Webhook 事件** | 可靠但**可能乱序、可能重复** |
| 4 | 本地操作提交记录 | 证明"我们发出过什么"，不证明远端结果 |
| 5 | 客户回跳 / HTTP 状态码 | **不作为**资金事实 |

判定规则：

1. **同级别之间按 `fetched_at` / 事件 `created` 取较新者**，但仅当对象版本可比。
2. **不同级别冲突时，低级别不覆盖高级别**。例如事件说 `succeeded`、查询说 `requires_payment_method`，以查询为准并重新取证。
3. 出现**无法调和**的矛盾（如 BT 显示已收款但查询显示会话未支付）→ `resolution_status = CONFLICT`，冻结自动处理，开 `STATUS_CONFLICT` 差错，人工调查。
4. **禁止"后到消息覆盖先到消息"**作为通用策略（PRD FR-PAY-03 明确否定）。

## 7. 与其它对象的边界

| 事实 | 影响 Payment 吗 | 影响什么 |
|---|---|---|
| 退款成功 | ❌ 不改 `payment_status` | `refund_control` 的 R；`refundable` 派生值 |
| 退款预占 | ❌ | `refund_control` 的 H |
| 争议扣款 | ❌ | `dispute` 对象 + PSP 分录 + 分润阻断 |
| 收款 BT 到达 | ❌ 不改 `payment_status` | `financial_status`：`PENDING_EVIDENCE` → `POSTED` |
| payout | ❌ 完全无关 | PSP → 在途 |

**`capturedAmountMinor`（C）来自最终 capture 事实**，不是最初请求金额（PRD FR-PAY-01）。多次 attempt 中只有成功 capture 的那次计入 C。

## 8. 状态图

```text
                    ┌──────────┐
                    │ CREATED  │◄── T1 事务提交（订单+操作+执行义务）
                    └────┬─────┘
             T2 ┌────────┼────────┐ T3（超时 → resolution=UNKNOWN）
                ▼        │        ▼
      ┌───────────────┐  │  ┌────────────┐
      │AWAITING_      │  │  │ PROCESSING │
      │CUSTOMER       │◄─┼──┤            │ T11（权威查询显示会话仍 open）
      └───┬───────┬───┘  │  └─────┬──────┘
       T4 │       │ T5   │        │ T7（Charge captured）
          ▼       └──────┼───────►│
   ┌──────────────┐      │        ▼
   │REQUIRES_     │──T6──┘  ┌───────────┐
   │ACTION        │         │ SUCCEEDED │ ◄── T8（事件先于中间态）
   └──────────────┘         └───────────┘
          │ T9（会话 OPEN 被 expire 且无资金事实）
          ▼
   ┌───────────┐
   │ CANCELED  │ ◄── T10（确证请求从未发出）
   └───────────┘

   ✗ SUCCEEDED 不可转出（R1）
   ✗ 超时/回跳/取消意图 均不可直接推出 CANCELED（R3/R4/R5）
```
