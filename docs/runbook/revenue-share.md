# PR12 内部分润运行说明

关联：[分润账务政策](../ledger/accounts.md)、[I-04 上限](../ledger/invariants.md)、[锁顺序](../adr/0007-lock-order-and-isolation.md)、[退款流程](refunds.md)。

## 计提规则

分润读取订单创建时保存的 `share_policy_snapshot`，不读取后来改动的最新政策。旧快照的 `partner_ref` / `share_basis_points` 仍受支持；多合作方使用 `allocations` 数组，每项包含这两个字段，政策版本使用顶层 `policy_version`。合作方不能重复，各比例必须是整数且合计不超过 10000 基点。

只有可信收款 BT 已正式入账、总本金与已确认 capture 一致、支付和资金事实无冲突且无阻断时，才使用 `eligibleBase=max(0,C-R-H)`。每个合作方上限为 `floor(eligibleBase*rateBps/10000)`，余数归平台。退款拆成多次时仍按当前累计本金计算，不累加逐笔退款的舍入结果。

例如 capture 10000、比例 2000 基点：初次计提 2000；预占退款 4000 时立即撤回 800，有效计提变为 1200；退款成功仅 H 转 R，保持 1200；明确失败或取消释放 H 后恢复 800。UNKNOWN 保留 H，不能提前恢复。

## 账本与原子性

计提借记 `PARTNER_SHARE_EXPENSE`、贷记 `PARTNER_PAYABLE`；撤回追加方向相反的新 Journal。两类科目是组织/环境/币种下的内部科目，`provider_account_id` 为空；合作方明细由 allocation、adjustment 和凭证事实保存。分润不会改动 PSP 资产，也不表示已经向合作方打款。

稳定事件键包含支付、合作方、快照版本和已持久化的 allocation generation。重复处理无金额变化时不产生新凭证。旧 Journal 与 adjustment 历史不可修改，数据库在提交时核对历史净额、凭证链接和当前上限。

退款申请、规范观察、明确拒绝、冲突冻结及审计解冻均在原事务内完成分润调整。收款 BT 与首次计提也在同一事务内完成；任何一侧失败都会完整回滚。支付、控制行及 allocation 锁先于账本锁；批量导入先按统一顺序锁定所有相关支付，再保存冲突并调整分润。规范终态通知延迟到事务提交阶段写入 Outbox，仍与状态和分润同事务。

已入账 BT 后到的金额/费用/关联矛盾会保留原 Journal，同时撤回分润；外部退款冲突也会冻结并撤回。后续调查不能直接改旧账或删除矛盾证据来恢复额度。异常 BT 即使无法归类为正常收款或退款，也通过规范来源定位支付，隔离与撤回同事务完成。

支付与退款绑定 Provider 来源前，会先锁定已存在的相关 BT，按渠道 BT ID 的 C 排序加锁，与批量导入顺序一致。导入和重放在持有 BT 锁后复查来源归属；若归属在最初查询后发生变化，则回滚本地事务并重新按顺序获取业务锁，最多尝试三次。该重试不重复 Provider 请求。

## 启用、补计提与查询

应用默认 `platform.revenue-share.enabled=true`，可通过 `REVENUE_SHARE_ENABLED` 配置；正常应用已接入所有事务入口。关闭开关不会撤销已有分润，也不能绕过数据库上限约束，不能把它当作有分润余额账户的退款应急方案。保留的旧组件构造方式用于未启用分润的独立测试。

Worker 在启用财务导入并配置组织、环境和 Provider 账户后，每次对至多 25 个历史支付补计提；默认间隔 300000ms，可用 `REVENUE_SHARE_POLL_MS` 配置。失败支付的尝试优先级会持久化轮转，避免单个错误快照阻塞后续支付。重复 catch-up 不重复计提。该任务用于历史缺项，退款提交时的上限由事务接线保护。

`RevenueShareService.allocations(TenantContext,paymentId)` 与 `adjustments(...)` 提供内部 scoped 查询，供 PR16 接入权限控制后的运营入口。本期没有合作方实付 API、Stripe Connect 调用或对外分润管理页面。

`ShareEligibilityPolicy` 已由 PR13 接入争议阻断，胜诉需补齐资金证据且清除所有阻断后才能恢复；详见[争议运行说明](disputes.md)。PR12 原始验证来源见 [PR12 验证报告](../evidence/pr12/README.md)，新增跨域验收见 [PR13 验证报告](../evidence/pr13/README.md)。
