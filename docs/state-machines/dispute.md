# 状态机：Dispute

版本：PR00 · 状态：冻结设计，未实施

关联：[PRD §7](../product/payment-platform-prd.md) · [账本科目](../ledger/accounts.md) · [手算示例争议分支](../ledger/posting-examples.md) · [退款状态机](refund.md) · [不变量 I-04](../ledger/invariants.md)

## 1. 三个维度分离

争议是本项目状态与资金最容易被混为一谈的地方。**必须分三条线记录：**

| 维度 | 字段 | 取值 | 谁写入 |
|---|---|---|---|
| 提供方状态 | `provider_status` | Stripe 原值（`warning_needs_response` / `needs_response` / `under_review` / `won` / `lost` 等），**原样保留** | 查询/已验签事件 |
| 本地处理 | `local_handling` | `NEEDS_REVIEW` / `WAITING_EVIDENCE` / `RESOLVED` | 运营 |
| 资金事实 | `dispute_financial_fact` 行 | 扣款 / 返还 / 损失重分类 | **仅**由 BT 驱动 |

**状态变化本身从不产生分录。** 只有真实 BT 才引起 PSP 资产变动（PRD FR-DIS-01、AC-V1-24）。

## 2. 资金侧的四个量

| 量 | 含义 |
|---|---|
| `disputed_principal` | 争议本金（provider 给出的金额） |
| `outstanding_receivable` | 已扣款但尚未解决的应收（`DISPUTE_RECEIVABLE` 余额） |
| `confirmed_loss` | 败诉后已确认的损失（`DISPUTE_LOSS` 余额） |
| `dispute_fee` | 实际争议费用，**独立记账**，不与本金混算 |

不变式：扣款发生后，`outstanding_receivable + confirmed_loss + 已返还本金 = 已扣本金`。

## 3. 允许的资金转换

| # | 触发事实 | 分录 | 前置 |
|---|---|---|---|
| D1 | inquiry / warning 开案 | **无分录** | 无 BT 即无资金变动 |
| D2 | 正式争议扣款 BT | 借 `DISPUTE_RECEIVABLE` 本金；借 `DISPUTE_FEE_EXPENSE` 实际费用；贷 `PSP_FUNDS` 合计 | 必须有真实 BT |
| D3 | 胜诉返还本金 BT | 借 `PSP_FUNDS`；贷 `DISPUTE_RECEIVABLE` | 必须有返还 BT |
| D4 | 败诉且本金仍在应收 | 借 `DISPUTE_LOSS`；贷 `DISPUTE_RECEIVABLE` | **内部重分类**，不再扣 PSP |
| D5 | 败诉后 late win 返还 BT | 借 `PSP_FUNDS`；贷 `DISPUTE_LOSS`（关联原损失） | 追加分支，不修改旧账 |
| D6 | 费用返还 BT | 借 `PSP_FUNDS`；贷 `DISPUTE_FEE_EXPENSE` | **仅**在有实际证据时 |

**D3 与 D4 互斥**（胜诉/败诉二选一），**D5 只能接在 D4 之后**。不得把正常胜诉返还与 late win 返还各加一次（验收 §5.2）。

### 3.1 返还款的分配优先级

收到本金返还时，按固定优先级拆分：

```text
尚存应收 (outstanding_receivable)
   → 已确认损失 (confirmed_loss)
      → 超额部分进 SUSPENSE 并开差错
```

示例（验收 §5.2 microfixture）：尚存应收 1000、已确认损失 2000，收到返还 3500：

```text
借 PSP_FUNDS        3500
  贷 DISPUTE_RECEIVABLE 1000
  贷 DISPUTE_LOSS       2000
  贷 SUSPENSE            500
```

**不得直接贷损失 3500。** 超额差错未解决前，不据此自动恢复分润。

## 4. 被拒绝的行为

| # | 被拒绝的行为 | 原因 |
|---|---|---|
| E1 | inquiry/warning 就记资金扣减 | 无 BT 即无资金移动（AC-V1-24） |
| E2 | 败诉时再扣一次 `PSP_FUNDS` | 扣款在 D2 已发生；败诉只是内部重分类 |
| E3 | 凭 `won` 状态恢复资金而无返还 BT | 状态不是资金证据 |
| E4 | 硬编码"胜诉退回所有费用" | 费用返还必须取实际 BT/`fee_details` |
| E5 | 假设争议金额 <= 原支付额 | 异常金额进人工解释流程 |
| E6 | 假设一个 Charge 只有一项争议 | 允许多项争议 |
| E7 | 把争议金额直接加进 R | 争议不是退款 |
| E8 | 开放争议期间允许本系统新发普通退款 | 必须阻断 |
| E9 | 修改旧凭证来反映新结论 | 一律追加新凭证 |
| E10 | `lost` 之后永久禁止 `won` | late win 是合法分支 |

## 5. 幂等重复

| 场景 | 期望行为 |
|---|---|
| 同一争议事件重投 | 规范事件键去重，无二次效果 |
| 扣款 BT 重复导入 | 一个 BT 一个 Journal，PSP 只变一次 |
| 重复的 `won` / `lost` 通知 | 条件更新命中同值，不重复重分类 |
| 重复 late win 返还 BT | 按 BT 规范键去重 |
| 内部损失重分类重放 | 稳定内部 `event_key`，不重复重分类 |

## 6. 对退款与分润的阻断

| 争议情形 | 对退款 | 对分润 |
|---|---|---|
| 开放争议（含 inquiry/warning） | **阻止**本系统新发普通退款 | `eligibleBase = 0`，撤回全部尚未实付计提 |
| 败诉 | 阻止 | `eligibleBase = 0` |
| 未解决资金矛盾 | 阻止 | `eligibleBase = 0` |
| 胜诉 **且** 资金/账务证据齐备 **且** 无其它阻断 | 恢复 | 按同一政策**恢复一次**计提 |

这是**保守业务政策**，并不假设争议金额等于剩余未退款金额（PRD FR-SHARE-01）。部分退款后发生争议也遵守同样政策，**不用 `R + 争议金额` 简单相加**。

外部已发生的退款与争议重叠时：**两份事实都保留**，冻结并调查，不强行合并（AC-V1-25）。

## 7. 状态图

```text
  ┌──────────────────────────┐
  │ 开案（inquiry / warning）│ ── D1：无分录，无 PSP 变动
  └────────────┬─────────────┘
               │ 阻断生效：eligibleBase=0，撤回计提，禁新退款
               ▼
  ┌──────────────────────────┐
  │ 正式扣款 BT 到达          │ ── D2：借应收+借费用，贷 PSP
  └──────┬────────────┬──────┘
     D3  │            │  D4
  （胜诉返还BT）   （败诉，内部重分类）
         ▼            ▼
  ┌───────────┐  ┌──────────────┐
  │ 应收=0     │  │ 损失=本金     │
  │ PSP 恢复   │  │ PSP 不再变动  │
  └─────┬─────┘  └──────┬───────┘
        │               │ D5（late win 返还 BT）
        │               ▼
        │        ┌──────────────┐
        │        │ 损失净额=0    │
        │        │ PSP 恢复      │
        │        └──────┬───────┘
        └───────────────┤
                        ▼
        证据齐备 + 无其它阻断 → 分润恢复一次

   ✗ 胜诉与败诉互斥；late win 只接在败诉之后
   ✗ 争议费用不随本金自动返还（E4）
```
