# PR14 Payout 与模拟银行证据

关联：[Payout 状态机](../state-machines/payout.md)、[账本科目](../ledger/accounts.md)、[I-06](../ledger/invariants.md)、[财务摄入](bt-ingestion.md)、[锁顺序](../adr/0007-lock-order-and-isolation.md)。

Payout 是平台 PSP 账户向平台银行账户的出款。PR14 实现摄入、查询、实际 BT 入账与模拟银行匹配。银行来源固定为 **SIMULATED_BANK_EVIDENCE**；不是银行直连。

## 状态与资金分别确认

`provider_status` 保存 pending/in_transit/paid/failed/canceled 及原始未知观察；`bank_match_state` 为 UNMATCHED/PARTIAL/MATCHED/CONFLICT。`payout.paid` 只更新提供方状态，银行现金仍须可信银行行匹配才能入账。

| 事实 | 借方 | 贷方 |
|---|---|---|
| 实际 payout 扣减 BT，本金5700 | BANK_PAYOUT_IN_TRANSIT 5700 | PSP_FUNDS 5700 |
| 实际 PSP 出款费30 | PSP_FEE_EXPENSE 30 | PSP_FUNDS 30 |
| 模拟银行确认本金5700，无银行费 | BANK_CASH 5700 | BANK_PAYOUT_IN_TRANSIT 5700 |
| 银行本金5700、银行费101 | BANK_CASH 5599 + BANK_FEE_EXPENSE 101 | BANK_PAYOUT_IN_TRANSIT 5700 |
| 未确认银行时的失败返还 BT5700 | PSP_FUNDS 5700 | BANK_PAYOUT_IN_TRANSIT 5700 |
| 银行已全额匹配后又出现返还 BT5700 | PSP_FUNDS 5700 | SUSPENSE 5700 |
| 随后取得显式银行退回行5700 | SUSPENSE 5700 | BANK_CASH 5700 |

返还的带符号实际费用单列；胜败状态不能推导费用退回。费用不占额外本金。所有旧凭证保留；重试使用稳定 BT 或银行事件键。银行先到账后返还、或返还后到银行记录，保存冲突和两侧证据；没有银行退回证据时不减少银行现金。银行退回后本金关系可以闭合，但调查记录和冲突状态继续保留供后续运营处理。

本地控制行强制 `outgoing_minor = transit_minor + bank_confirmed_minor + returned_minor`，各分配均不为负。`suspense_minor` 单列保留尚未解释的返还，不能同时算满额银行确认和满额本金返还。完整外部守恒仍须两侧证据；提交约束本身不宣称外部证据完整。

## 摄入与恢复

PayoutWebhookHandler 接入已验签 Inbox，业务观察与 DONE 在同一事务提交，旧租约不能生效。FinancialReplayService 在 BT 锁前发现并锁定 payout；款项先到时先保存依赖，再由现有财务 Worker 在事务外查询来源后重放。失败返还先于扣减时继续 PENDING_EVIDENCE。

PayoutRecoveryWorker 与财务摄入使用独立的 `financialIngestionScheduler`。启用条件为 worker profile、`platform.financial-ingestion.enabled=true` 及可信组织/环境/账户配置。默认每300000ms，`platform.payout.poll-ms` 可调整。列表用七天 UTC 重叠窗口，每次最多处理五个分页任务和25个已知 payout；失败或不可用读取推进尝试时间以轮转。网络调用发生在本地事务外。

列表读取保存请求范围、原始响应证据、API版本、游标和完成度。只有页面协议、版本和事实通过检查才推进游标；对象逐个在自己的事务内规范接收，游标最后提交，崩溃后重复接收保持幂等，避免跨父记录倒序锁。所有源证据为追加记录。

自动 payout 的所含交易仅来自 Provider 的 payout-filtered 查询。每个新的 payout 观察版本都可触发新归属查询，包括 `payout.reconciliation_completed`，早期空页不会永久阻止后续恢复。归属事实比较不可变金额和来源，pending→available 不引发冲突。手动 payout 不查询或猜测官方交易归属；没有 FIFO 映射。归属页面和 payout 自身负 BT 分开保存，归属读取不再次入账或重复抵扣。

## 固定模拟银行格式与内部匹配

[固定 schema](../fixtures/bank-statement-v1.schema.json)与[稳定 ID 示例](../fixtures/bank-statement-v1.example.json)。导入只接受 schemaVersion=1、明确 simulation 标签、测试 ProviderScope、启用币种、稳定 fixtureId/bankAccountRef/lineId、半开 UTC 范围、整数本金和费用、完整性标志及显式 payoutReferences。未知字段、浮点金额、重复源行、越界时间及身份变化整批拒绝；JSON 字段顺序、行序、引用顺序或币种大小写变化经规范化后不会造成重复导入。

银行行类型 CREDIT/RETURN。同一 CREDIT 行可以按明确的多个 payout 引用拆分；每个 payout/line/kind 组合使用一次稳定事件键，同键不同金额拒绝。多行可覆盖同一 payout。累计分配不能超过银行行本金或 payout 在途。拆分费用按 `floor(实际费用×累计已用本金/银行行本金)` 的增量分配，所有拆分最终精确等于实际费用，不重复扣费。RETURN 行费用为0，必须有对应 SUSPENSE 和净银行本金容量才能追加退回调整。

内部调用 `BankStatementImporter.importStatement(scope, json)` 返回 statement UUID；行 UUID 可从导入结果记录查询。`BankMatcher.match(scope, payoutUuid, lineUuid, principalMinor)` 验证组织、环境、提供方账户、币种、显式引用、到账日期前后七个 UTC 日期和本金上限。锁顺序为 payout→银行行→账本账户全局 UUID 顺序；金融事件由该父记录控制串行化。投影只经 LedgerPostingService 更新。

`BankMatcher.evidenceState` 区分 MATCHED、RETURNED、CONFLICT、WAITING_EVIDENCE 和 SHORT_RECEIPT。缺银行数据、未完成范围或尚未分配的来源行继续 WAITING_EVIDENCE。银行完整范围覆盖预期到账日、源行满足该 payout 的日期窗口且已有该 payout 的真实分配、相关源行已分配且在途仍有余款时，才给出本期 SHORT_RECEIPT 证据；PR15 接完整核对和费用模型。

内部 scoped 服务为 PR16 运营接口准备。本期没有公开银行导入 API，也没有新增主动外部提现写接口。主动测试 payout 是可选能力，本期 N/A；真实账户只读列表结果和未运行路径见[PR14 证据](../evidence/pr14/README.md)。
