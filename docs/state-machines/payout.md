# 状态机：Payout 与银行匹配

版本：PR00 · 状态：冻结设计，未实施

关联：[PRD §8](../product/payment-platform-prd.md) · [账本科目](../ledger/accounts.md) · [不变量 I-06](../ledger/invariants.md) · [手算示例路线 A / §5.3](../ledger/posting-examples.md)

## 1. 定义边界

> **Payout = Stripe 把平台 Stripe 账户中的钱转到平台绑定的银行账户。**

它**不是**给合作方分润，**不是**收款 capture，**不是**客户退款。本期实现摄入与查询；主动发起测试 payout 仅在账户能力支持时可选，缺该能力**不虚构实测**。

## 2. 两个正交维度

| 维度 | 字段 | 取值 |
|---|---|---|
| 提供方状态 | `provider_status` | `pending` / `in_transit` / `paid` / `failed` / `canceled` |
| 银行匹配 | `bank_match_state` | `UNMATCHED` / `PARTIAL` / `MATCHED` / `CONFLICT` |

**`paid` 不等于 `MATCHED`。** `payout.paid` 只是提供方声明已完成出款，它**不直接**借记 `BANK_CASH`（PRD FR-PO-02、AC-V1-26）。这是本状态机存在的核心理由。

## 3. 本金守恒（I-06）

对单个 payout：

```text
已从 PSP 转出的本金
   = 尚在途本金
   + 已确认银行到账本金
   + 已确认返还 PSP 本金（含经验证调整）

费用（PSP 出款费、银行侧费用）单列，不占用本金、不重复扣除。
```

**可提交拒绝的部分**：累计本金分配 ≤ 已转出本金，在匹配事务内即可强制。
**必须等证据的部分**：完整守恒需 PSP 与银行两侧证据齐备，缺证据为 `INCOMPLETE`，不是 `PASS`。

## 4. 允许的资金转换

| # | 触发事实 | 分录 | 前置 |
|---|---|---|---|
| P1 | payout 扣减 BT 出现 | 借 `BANK_PAYOUT_IN_TRANSIT` 本金；贷 `PSP_FUNDS` | 必须有真实扣减 BT |
| P2 | 实际出款费 | 借 `PSP_FEE_EXPENSE`；贷 `PSP_FUNDS` | 独立记账 |
| P3 | 银行行核对到账 | 借 `BANK_CASH`；贷 `BANK_PAYOUT_IN_TRANSIT` | 可信银行 fixture 证据 + 分配通过 |
| P4 | 银行侧费用 | 借 `BANK_FEE_EXPENSE`；贷 `BANK_CASH` | 有银行证据 |
| P5 | payout 失败/取消返还 | 借 `PSP_FUNDS`；贷 `BANK_PAYOUT_IN_TRANSIT` | 按 `failure_balance_transaction` |
| P6 | 已匹配银行后又出现返还（冲突） | 借 `PSP_FUNDS`；贷 `SUSPENSE` | 在途已为 0 时的暂记规则（§7） |
| P7 | 银行确实退回 | 借 `SUSPENSE`；贷 `BANK_CASH` | 取得银行退回证据后 |

**P1 与 P5 是不同 fixture 的互斥分支**（除非 §7 的冲突场景）。不得把路线 A 的"银行=5700"套用到失败分支（验收 §5.1）。

## 5. 被拒绝的行为

| # | 被拒绝的行为 | 原因 |
|---|---|---|
| Q1 | `payout.paid` 直接借 `BANK_CASH` | 提供方状态不是银行证据 |
| Q2 | 用本地手工删除历史扣减来表达失败 | 一律追加返还分录 |
| Q3 | 同一银行行被两个 payout 各算满额 | 来源约束 + 累计分配上限（I-06） |
| Q4 | 把"确认到账 5700 + 确认返还 5700"都算进同一 payout 的本金分配 | 双算 |
| Q5 | 把在途减成负数以"假装匹配成功" | 拒绝；用 `SUSPENSE` 暂记 |
| Q6 | 手动 payout 用 FIFO 猜测官方所含订单 | 只有提供方明确给出归属才映射 |
| Q7 | 把窗口内 payout 负 BT 与所含交易净额在同一批次重复抵扣 | 分别计算 |
| Q8 | 缺银行证据就判 `SHORT_RECEIPT` | 应为 `WAITING_EVIDENCE` |
| Q9 | `payout` 状态 `UNKNOWN` 或逾期期间自动再出款 | 保留在途，不自动重发 |
| Q10 | 用预期费率模型覆盖实际分录 | 模型不足标 `UNMODELED_FEE` |

## 6. 少结算判定门槛

"payout 小于毛收款"**不自动**是少结算。先扣除真实退款、费用、争议、其他调整、不可用余额与留存，再解释净额差异。

| 判定 | 条件 |
|---|---|
| `WAITING_EVIDENCE` | 未到银行、银行证据缺失、pending 余额尚未可出款 |
| `SHORT_RECEIPT` | 币种、参考号、时间窗与**完整**银行证据均可确认后，实收仍不足 |
| `UNMODELED_FEE` | 实际费用与版本化费率预期不符，且预期模型不完整 |

```text
预期净银行到账 = payout 本金 − 已被证据确认且未重复入账的银行侧费用
```

## 7. 已匹配后又收到返还（冲突 fixture）

这是 `CONFLICT` 状态的典型来源（验收 §5.3）。原本金 5700 已匹配银行，随后发现真实返还 BT 5700：

| 步骤 | 动作 |
|---|---|
| 1 | 锁定 payout 分配控制行，阻止超额分配与新自动出款；置 `bank_match_state=CONFLICT` |
| 2 | **两份事实都保留**——不能把"确认到账 5700 + 确认返还 5700"当成该 payout 的本金分配 |
| 3 | 真实且已核验本域的 PSP 返还必须保留资金影响；在途已为 0 时按 P6 暂记 `SUSPENSE`，**不把在途减成负数** |
| 4 | 取得银行退回证据后按 P7 追加调整，得到银行净到账 0、PSP 返还 5700 |
| 5 | 没有银行证据时继续等待/冲突，**不先假定银行现金已减少** |

原银行入账 Journal 与原 payout Journal **均不删除**。

## 8. 自动与手动 payout

| 类型 | 交易归属 | 核对方式 |
|---|---|---|
| 自动 | 提供方可按 payout 查询所含交易 | 批次归属核对 |
| 手动 | **无**官方归属 | 只做余额级核对，不伪造订单归属 |

自动 payout 的**所含交易净额**与**payout 自身的负 BT** 必须分别计算，避免在同一批次汇总中重复抵扣（PRD §11、FR-REC-03）。

## 9. 状态图

```text
  provider_status:  pending ──► in_transit ──► paid
                        │                        │
                        └────────► failed / canceled
                                      │
  ─────────────────────────────────────────────────────────
  资金侧（由 BT 驱动，与 provider_status 正交）：

   扣减 BT 到达
        │ P1：借在途，贷 PSP
        ▼
  ┌───────────────┐
  │ 在途 = 本金   │  bank_match_state = UNMATCHED
  └───┬───────┬───┘
   P3 │       │ P5（失败返还 BT）
      ▼       ▼
 ┌─────────┐ ┌──────────────┐
 │BANK_CASH│ │ PSP 恢复      │
 │ MATCHED │ │ 在途=0        │
 └────┬────┘ └──────────────┘
      │ 又收到返还 BT（冲突）
      ▼
 ┌──────────────────────────────┐
 │ CONFLICT：P6 暂记 SUSPENSE    │
 │ 两份事实并存，等银行退回证据  │
 └──────────────────────────────┘

  ✗ paid 不产生 P3（Q1）
  ✗ 在途不得为负（Q5）
```
