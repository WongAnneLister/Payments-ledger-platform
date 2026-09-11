# 状态机：Refund 与 RefundControl

版本：PR00 · 状态：冻结设计，未实施

关联：[PRD §6](../product/payment-platform-prd.md) · [OpenAPI](../contracts/openapi.yaml) · [操作状态机](provider-operation.md) · [不变量 I-03](../ledger/invariants.md) · [手算示例路线 B](../ledger/posting-examples.md)

## 1. 两个对象，不要合并

| 对象 | 粒度 | 承载 |
|---|---|---|
| `refund_control` | **每笔支付一行** | C、R、H 三元组 + `control_state`；并发退款竞争锁定的稳定父行 |
| `refund` | 每次退款一行 | 单笔退款的业务/提供方状态、金额、证据 |

`refund_control` 是**并发控制的锚点**。所有退款命令都先锁定它，这是 I-03 在提交时可强制的根本原因（AC-V1-19）。

## 2. 额度定义

| 符号 | 字段 | 含义 |
|---|---|---|
| C | `captured_minor` | 确认的累计 capture 金额（本期一笔支付仅一次成功 capture） |
| R | `refunded_minor` | **成功**退款累计 |
| H | `reserved_minor` | 本地已批准但尚未明确失败/成功的预占 |

`NORMAL` 状态下必须同时满足：

```text
C >= 0   R >= 0   H >= 0          ← 三者各自非负
0 <= R + H <= C                    ← 总额约束
```

**必须分别检查三者非负**，不能只检查 `R + H` 的和——否则一个负数可以抵消另一个超额值（验收 §3 明确要求）。

## 3. RefundControl 的 control_state

| 状态 | 含义 | 是否允许新退款 |
|---|---|---|
| `NORMAL` | 额度约束成立，本地权威 | ✅ |
| `EXTERNAL_CONFLICT` | 已摄入 Dashboard 外部退款，与本地预占映射未解决 | ❌ |
| `DISPUTE_BLOCKED` | 存在开放争议或未解决资金矛盾 | ❌ |

转换规则：

| From | To | 触发 | 说明 |
|---|---|---|---|
| `NORMAL` | `EXTERNAL_CONFLICT` | 摄入外部退款且无法唯一映射到已有预占 | **外部事实优先**；冻结新退款并调查 |
| `EXTERNAL_CONFLICT` | `NORMAL` | 完成映射核对并用幂等调整修正 C/R/H | 需审计记录 |
| `NORMAL` | `DISPUTE_BLOCKED` | 争议生效（PR13） | 同时使 `eligibleBase=0` |
| `DISPUTE_BLOCKED` | `NORMAL` | 全部阻断清除且证据齐备 | 分润按同一政策恢复一次 |

**禁止**为了维持表面额度而丢弃已证实的外部退款事实（AC-V1-20、I-03 失败处理）。

## 4. Refund 状态

| 状态 | 含义 | H 是否保留 |
|---|---|---|
| `RESERVED` | 已预占，尚未调用 Stripe | ✅ |
| `SUBMITTED` | 已发送，等待结果 | ✅ |
| `PENDING` | 提供方处理中 | ✅ |
| `REQUIRES_ACTION` | 需额外动作 | ✅ |
| `SUCCEEDED` | 明确成功 | H → R |
| `FAILED` | 明确失败 | 释放 H |
| `CANCELED` | 明确取消 | 释放 H |

**只有明确的 `FAILED` / `CANCELED` 才释放 H。** 超时、pending、requires_action、UNKNOWN 一律**保留 H**（PRD FR-REF-01、AC-V1-20）。

## 5. 允许的转换

| # | From | To | 触发 | 额度动作 | 同事务要求 |
|---|---|---|---|---|---|
| F1 | — | `RESERVED` | `POST /v1/refunds` | H += amount | 锁 control 行 + 校验 + 写 refund/operation/义务 + **分润调整** + Outbox |
| F2 | `RESERVED` | `SUBMITTED` | Worker 取得租约并发送 | 无 | 调用在事务外 |
| F3 | `SUBMITTED` | `SUCCEEDED` | 提供方确认成功 | H -= a; R += a | 条件更新 + 分润不重复撤回 + Outbox |
| F4 | `SUBMITTED` | `PENDING` | 提供方处理中 | 无（保留 H） | — |
| F5 | `PENDING` | `SUCCEEDED` | 最终成功 | H → R | 同 F3 |
| F6 | `SUBMITTED`/`PENDING` | `FAILED` | **明确**失败 | H -= a | 释放 H + **幂等恢复分润** + Outbox |
| F7 | `SUBMITTED`/`PENDING` | `CANCELED` | **明确**取消 | H -= a | 同 F6 |
| F8 | `SUBMITTED` | `SUBMITTED`(UNKNOWN) | 超时/暂态 | 无（保留 H） | 置 `resolution=UNKNOWN` |
| F9 | 任意非终态 | `SUCCEEDED`/`FAILED` | 恢复流程取证后归并 | 按 F3/F6 | 经 `provider_operation` 收敛 |

**F1 是全项目最密集的一个事务**，一次提交同时完成：额度预占、退款单、通用操作、持久执行义务、分润上限调整（PR12 起）、Outbox。任一步失败则整体回滚，**不留下孤立预占或孤立分润凭证**（AC-V1-19）。

## 6. 被拒绝的转换

| # | 被拒绝的行为 | 原因 | 响应 |
|---|---|---|---|
| N1 | 预占使 `R + H > C` | 超额退款 | `422 REFUND_LIMIT_EXCEEDED`，无任何残留 |
| N2 | 金额 <= 0 | 必须为正 | `400 VALIDATION_ERROR` |
| N3 | `control_state != NORMAL` 时新建退款 | 争议阻断/外部冲突 | `409 REFUND_BLOCKED` / `EVIDENCE_CONFLICT` |
| N4 | UNKNOWN/pending 时释放 H | 会导致超额退款 | 拒绝；保留 H |
| N5 | 因本地预占而减少 PSP 资产 | 预占不是资金移动 | 拒绝；资金只依 BT |
| N6 | 按退款比例自动退回原处理费 | 旧政策已废止 | 拒绝（FR-REF-02） |
| N7 | 同一退款同时计入 R 和 H | 双算 | 条件更新保证互斥 |
| N8 | 删除外部退款事实以维持乐观额度 | 外部事实优先 | 拒绝；转 `EXTERNAL_CONFLICT` |
| N9 | 退款成功 H→R 时再次撤回分润 | 重复撤回 | 拒绝（PR12 接线要求） |
| N10 | 币种与原支付不一致 | 跨币种 | `422 CURRENCY_NOT_ENABLED` |

## 7. 幂等重复

| 场景 | 期望行为 |
|---|---|
| 重复的 `refund.succeeded` 事件 | 条件更新命中同值；**不**再次 H→R、**不**重复通知 |
| 重复的失败通知 | 不再次释放 H、不重复恢复分润 |
| 成功之后到达的旧 `pending` 状态 | 低优先级证据，不回退终态 |
| 同 `Idempotency-Key` 同内容重放 | 返回原退款资源 |
| 同 `clientRefundKey` 重复提交 | 本地唯一键命中，返回原资源 |
| 退款 BT 重复导入 | 规范财务键去重，只有一个 Journal（I-02） |

## 8. 证据优先级

与 Payment 同一套分级，但有一条**退款特有**的规则：

> **退款业务状态与退款 BT 可以长期不同步，这不是错误。**

| 组合 | 含义 | 处理 |
|---|---|---|
| `SUCCEEDED` + 无退款 BT | 业务已成功，资金证据未到 | `financialStatus=PENDING_EVIDENCE`，双维度展示 |
| 退款 BT 已到 + 业务仍 `SUBMITTED` | 资金已动，业务状态待确认 | 按 BT 记账；**不**据此假装业务成功（路线 B 的 B3） |
| `FAILED` + 存在失败返还 BT | 失败且资金已退回 | 新 BT → **新 Journal**，不删旧账 |
| `FAILED` + 无任何 BT | 从未产生资金变动 | 不制造"一减一加"的假资金流 |

最后两行是路线 B 的两个子用例：**最终 PSP 都是 9700、分润都是 2000，但账务历史不同**（验收 §5.2）。

## 9. 状态图

```text
   POST /v1/refunds
        │  F1: 锁 control → 校验 → H+=a → refund/operation/义务/分润/Outbox（同事务）
        ▼
  ┌────────────┐
  │  RESERVED  │  H 保留
  └─────┬──────┘
        │ F2（租约 → 事务外调用）
        ▼
  ┌────────────┐  F8 超时/暂态
  │ SUBMITTED  │───────────► resolution=UNKNOWN（H 保留，不重发、不换键）
  └──┬──────┬──┘
 F4  │      │ F3
     ▼      │
 ┌─────────┐│
 │ PENDING ││
 └──┬───┬──┘│
 F5 │   │F6/F7（明确失败/取消）
    │   └──────────┐
    ▼              ▼
┌───────────┐  ┌──────────────────┐
│ SUCCEEDED │  │ FAILED / CANCELED│
│  H → R    │  │  释放 H + 恢复分润│
└───────────┘  └──────────────────┘

✗ UNKNOWN / PENDING / REQUIRES_ACTION 一律不释放 H（N4）
✗ 预占不产生 PSP 分录（N5）
```
