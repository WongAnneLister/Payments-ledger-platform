# 账本科目与会计政策

版本：PR03 · 状态：账本核心已实施；业务分录映射按后续 PR 接线

关联：[PRD §9](../product/payment-platform-prd.md) · [AccountScope](../contracts/account-scope.md) · [七项不变量](invariants.md) · [手算示例](posting-examples.md) · [ADR-0004 不可变账本](../adr/0004-immutable-ledger.md)

> **这是平台作为销售主体的简化演示会计政策，不是通用财务报表标准。**
> 费率、科目归类与分润规则是本项目的冻结政策，不代表 Stripe 实际报价或任何会计准则。

## 1. 科目表

所有科目都有 `organization_id`、`environment_id`、`currency`、`account_purpose`、`source_domain`，以及（适用时）`provider_account_id` 和 `balance_domain`。

| `account_purpose` | 类别 | 正常余额 | 含义 | `source_domain` | `provider_account_id` |
|---|---|---|---|---|---|
| `PSP_FUNDS` | 资产（控制账户） | 借 | Stripe payments balance 中尚未转出的钱 | `PROVIDER` | 必填 |
| `BANK_PAYOUT_IN_TRANSIT` | 资产 | 借 | 已从 PSP 扣出、尚未核对银行收到 | `PROVIDER` | 必填 |
| `BANK_CASH` | 资产 | 借 | 有银行证据确认的现金（**本期为模拟**） | `SIMULATED_BANK` | NULL |
| `DISPUTE_RECEIVABLE` | 资产 | 借 | 争议扣款后等待胜诉返还的本金 | `PROVIDER` | 必填 |
| `SALES_REVENUE` | 收入 | 贷 | 证据支持的毛收款确认 | `INTERNAL` | NULL |
| `SALES_RETURNS` | 收入抵减 | 借 | 实际退款本金 | `INTERNAL` | NULL |
| `PSP_FEE_EXPENSE` | 费用 | 借 | 实际 PSP 费用 | `PROVIDER` | 必填 |
| `DISPUTE_FEE_EXPENSE` | 费用 | 借 | 实际争议费用 | `PROVIDER` | 必填 |
| `DISPUTE_LOSS` | 费用 | 借 | 败诉后本金损失 | `INTERNAL` | NULL |
| `PARTNER_SHARE_EXPENSE` | 费用 | 借 | 内部分润计提 | `INTERNAL` | NULL |
| `PARTNER_PAYABLE` | 负债 | 贷 | 尚未对合作方实付的计提净额 | `INTERNAL` | NULL |
| `BANK_FEE_EXPENSE` | 费用 | 借 | 有银行证据的银行侧费用 | `SIMULATED_BANK` | NULL |
| `SUSPENSE` | 待分类 | — | 已知资金变化但业务归类未完成 | `PROVIDER` | 必填 |
| `OPENING_EQUITY` | 权益 | 贷 | 明确证据支持的期初 | `INTERNAL` | NULL |

### 1.1 两条关键约束

**`PSP_FUNDS` 可以为负。** Stripe 真实存在余额为负的情形（争议扣款、退款超过当期收款）。**禁止**设置"资产永不为负"的全局约束去拒绝真实扣款——那会把外部事实挡在账本之外。负余额产生告警并受控处理。

**合作方计提在本期禁止为负。** `PARTNER_PAYABLE` 与 `PARTNER_SHARE_EXPENSE` 的净额必须满足 `0 <= S_i <= cap_i`。

**`OPENING_EQUITY` 禁止用来掩盖差异。** 夜间检查中自动追加 `OPENING_EQUITY` 或随机调整来清零残差，一律视为严重缺陷（验收 §8）。

## 2. 分录规则表

每条规则对应一个**规范化财务事件**，事件与 Journal 一对一（I-02）。

| 规则 ID | 触发事实 | 借方 | 贷方 | 归属 PR |
|---|---|---|---|---|
| `L-CHARGE` | Charge BT（amount/fee/net） | `PSP_FUNDS` net；`PSP_FEE_EXPENSE` fee | `SALES_REVENUE` amount | PR10 |
| `L-REFUND` | Refund BT | `SALES_RETURNS` 本金 | `PSP_FUNDS` 本金 | PR10/PR11 |
| `L-REFUND-FEE-BACK` | 有证据的费用返还 BT | `PSP_FUNDS` | `PSP_FEE_EXPENSE` | PR11 |
| `L-REFUND-REVERSE` | 退款失败返还 BT | `PSP_FUNDS` | `SALES_RETURNS` | PR11 |
| `L-SHARE-ACCRUE` | 首次计提 / 恢复计提 | `PARTNER_SHARE_EXPENSE` | `PARTNER_PAYABLE` | PR12 |
| `L-SHARE-REVERSE` | 超出新上限的撤回 | `PARTNER_PAYABLE` | `PARTNER_SHARE_EXPENSE` | PR12 |
| `L-DISPUTE-DEBIT` | 争议扣款 BT | `DISPUTE_RECEIVABLE` 本金；`DISPUTE_FEE_EXPENSE` 费用 | `PSP_FUNDS` 合计 | PR13 |
| `L-DISPUTE-WON` | 胜诉返还 BT | `PSP_FUNDS` | `DISPUTE_RECEIVABLE` | PR13 |
| `L-DISPUTE-LOST` | 败诉（内部重分类） | `DISPUTE_LOSS` | `DISPUTE_RECEIVABLE` | PR13 |
| `L-DISPUTE-LATE-WIN` | 败诉后返还 BT | `PSP_FUNDS` | `DISPUTE_LOSS`（关联原损失） | PR13 |
| `L-PAYOUT-OUT` | payout 扣减 BT | `BANK_PAYOUT_IN_TRANSIT` | `PSP_FUNDS` | PR14 |
| `L-PAYOUT-FEE` | 实际出款费 | `PSP_FEE_EXPENSE` | `PSP_FUNDS` | PR14 |
| `L-BANK-IN` | 银行到账（模拟证据） | `BANK_CASH` | `BANK_PAYOUT_IN_TRANSIT` | PR14 |
| `L-BANK-FEE` | 银行侧费用 | `BANK_FEE_EXPENSE` | `BANK_CASH` | PR14 |
| `L-PAYOUT-RETURN` | payout 失败返还 BT | `PSP_FUNDS` | `BANK_PAYOUT_IN_TRANSIT` | PR14 |
| `L-PAYOUT-RETURN-SUSPENSE` | 已匹配银行后的返还（在途为 0） | `PSP_FUNDS` | `SUSPENSE` | PR14 |
| `L-BANK-CLAWBACK` | 银行退回证据到达 | `SUSPENSE` | `BANK_CASH` | PR14 |
| `L-SUSPENSE-IN` | 已验证 payments 域净增、业务类型未知 | `PSP_FUNDS` | `SUSPENSE` | PR10 |
| `L-SUSPENSE-OUT` | 已验证 payments 域净减、业务类型未知 | `SUSPENSE` | `PSP_FUNDS` | PR10 |
| `L-RECLASSIFY` | Suspense 查明归类 | 按目标科目 | `SUSPENSE`（或反向） | PR15/PR16 |
| `L-OPENING` | 经核对的期初 | 各资产科目 | `OPENING_EQUITY` | PR10 |
| `L-ADJUSTMENT` | 经**独立审批**的人工调整 | 按申请 | 按申请 | PR16 |

### 2.1 三条不能照抄示例的规则

1. **原支付处理费不随退款自动返还。** `L-REFUND` 只动本金；只有 `L-REFUND-FEE-BACK` 且**有实际证据**时才记回费用。旧 PRD"按退款比例退手续费"的政策**已废止**（FR-REF-02）。
2. **败诉不再扣 PSP。** `L-DISPUTE-LOST` 是 `DISPUTE_RECEIVABLE → DISPUTE_LOSS` 的内部重分类，PSP 在 `L-DISPUTE-DEBIT` 时已经扣过一次。
3. **争议费不随本金自动返还。** 需要 `fee_details` 级别的实际证据才记 `L-REFUND-FEE-BACK` 的争议版本。

## 3. 规范化财务事件键

`financial_event` 的唯一键为 `(organization_id, environment_id, event_key)`。`event_key` 的构造必须**稳定且可重放**——重试不得生成随机身份。

| 事件来源 | `event_key` 构造 |
|---|---|
| 外部 BT | `bt:{provider_account_id}:{livemode}:{bt_id}` |
| 内部分润计提 | `share.accrue:{payment_id}:{partner_id}:{policy_version}:{trigger_version}` |
| 内部分润撤回 | `share.reverse:{payment_id}:{partner_id}:{policy_version}:{trigger_version}` |
| 争议损失重分类 | `dispute.loss:{dispute_id}:{trigger_version}` |
| 银行匹配 | `bank.match:{payout_id}:{bank_statement_line_id}` |
| 银行退回 | `bank.clawback:{payout_id}:{bank_statement_line_id}:{trigger_version}` |
| 人工调整 | `adjust:{adjustment_id}` |
| 期初 | `opening:{account_scope_hash}:{cutoff}` |

**`trigger_version` 是持久化的业务触发版本**（例如 `refund_control.version`），不是时间戳、不是随机数。这保证"同一业务触发的重放产生同一事件键"（PRD FR-SHARE-01、AC-V1-23）。

> **注意**：外部资金事件的规范键是 **BT ID**，不是 Stripe Event ID。一个 Webhook 可以不记账，也可以引出多个尚未见过的 BT。**禁止**把每个 `evt_*` 当作独立会计事件（PRD §4.2）。

## 4. 余额重建口径（PR00 冻结默认）

余额重建只有两种**互斥**口径，必须显式选择，否则会双计期初：

| 口径 | 计算 | 何时使用 |
|---|---|---|
| **`OPENING_JOURNAL`（默认）** | 从 0 累计扫描集内**全部**不可变分录（期初已作为 `L-OPENING` 凭证入账并包含在扫描集中） | 本项目默认；空测试账户起步时期初为 0，无需额外凭证 |
| `EXTERNAL_OPENING_SNAPSHOT` | 经核对的期初快照 + cutoff 之后的分录 | 仅在期初**未**作为 Journal 计入扫描集时使用，**必须显式标记** |

**PR00 冻结默认口径为 `OPENING_JOURNAL`。** 验收另一种输入时必须在运行记录中显式标注，防止"全历史 BT 再加一次全额期初"的双计（验收 §3、PRD FR-REC-01）。

## 5. 过账服务边界

```text
                    ┌──────────────────────────┐
  BT Importer ─────►│                          │
  Webhook 处理 ────►│  LedgerPostingService    │──► journal + journal_entry
  分润服务 ────────►│  （唯一过账入口）         │──► balance_projection
  争议服务 ────────►│                          │      （同一事务）
  payout 匹配 ─────►│                          │
  审批后的调整 ────►└──────────────────────────┘

  ✗ Importer 不得在调用之后再累加一次 PSP 投影
  ✗ 任何服务不得直接 UPDATE balance_projection
  ✗ 应用代码不得绕过过账服务构建可提交的 journal / journal_entry
```

**余额投影只由 `LedgerPostingService` 在过账事务内更新。** 这是 AC-V1-17 与 I-05 的核心断言：Webhook、Importer、恢复任务、对账重跑同时接受同一个 BT 时，PSP 投影**只变化一次**（验收 §3.1 第 7 条）。

PR03 通过受控 `platform.post_ledger` 函数承载实际写入；运行角色仍有下面约定的 INSERT 权限，但无法自行封存或更新投影，直接构建的未封存凭证在提交时被拒绝。完整实现边界见[账本运行手册](../runbook/ledger-core.md)。

## 6. 数据库角色分离

| 角色 | 权限 |
|---|---|
| 迁移角色 | DDL；创建表、约束、触发器 |
| 应用运行角色 | 对 `journal` / `journal_entry` **只有 INSERT**，无 UPDATE / DELETE；`balance_projection` 仅通过过账路径写入 |
| 只读角色 | 运营查询、对账重建 |

测试**必须使用实际应用运行角色**验证防护，不能用 owner/superuser 绕过后声称"数据库已保护"（验收 §3.1）。

## 7. 必须尝试并失败的绕过路径

PR03 的测试必须逐条覆盖（验收 §3.1）：

1. 插入空 Journal、单条 Entry、不平衡组合 → 提交时失败
2. 一张凭证内混用组织 / 环境 / 币种 / 不兼容来源 → 失败且无投影与 Outbox 残留
3. 已封存后 `UPDATE` / `DELETE` Journal 或 Entry → 失败
4. 已封存后插入一条 Entry，**或额外插入一对借贷相等的 Entry** → 均失败（**仅保持平衡不足以保证不可变**）
5. 同事务内构造完整凭证并封存 → 成功；不存在外部可修改的已提交草稿 Journal
6. 相同规范事件重复请求 → 返回原 Journal；改其金额 / source / fee / net **不能**作为普通 duplicate 悄悄接受
7. 导入器 / Webhook / 恢复任务 / 对账重跑同时接受同一 BT → PSP 投影只变化一次
8. 在隔离 fixture 中故意改错投影 → 独立重建**必须**发现残差（这是故障构造，不提供生产"改余额"路径）
