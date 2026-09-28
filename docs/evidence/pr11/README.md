# PR11 退款闭环验证

基线 `7a1e84f2507b1a6bd1b50ebe15dfad93285af365`；开发分支 `codex/pr11-refunds`。

Java 21 执行 `./mvnw -B clean verify`：**441 passed，2 skipped，0 failure/error**。单元测试 184 项、集成测试 257 项通过。跳过项为需要显式启用的真实 Stripe 账户测试；**本期真实 Stripe 退款闭环为 NOT_RUN**，不得将本地 fixture 冒认真实联调。

使用真实 PostgreSQL 16.14 与受限 payments_app 角色、Kafka 3.9.0 容器、实际 HTTP API，以及独立 PostgreSQL 模拟商户。全量回归覆盖既有身份、支付、Webhook、消息与账本。

| 范围 | 实际测试证据 |
|---|---|
| C=1000 并发600+600、幂等键与商户退款键、各自非负、回滚无孤立预占 | RefundCoreIT |
| UNKNOWN保留H、同键恢复、过期不重发、旧租约仅留证、过期发送被拒 | RefundRecoveryIT |
| 已知ID查询、冲突ID冻结/旧租约保护/回滚、长期未知告警升级、L2缺BT、Dashboard退款query-only恢复 | RefundRecoveryIT |
| scoped API、201预占/200重放、金额校验、只写凭据、跨应用/组织拒绝 | RefundHttpIT / RefundRequestsTest |
| 回调重复/乱序、缺Charge等待、旧Inbox租约、ACK失败原子回滚、金额冲突 | RefundWebhookIT |
| BT先到、pending业务下真实扣款、失败新BT/新Journal、费用原值、独立投影重建、迟到冲突 | RefundFinancialIT / FinancialRulesTest |
| 外部退款冻结、保留原始观察、严格审计解冻、依赖补齐后可调查恢复 | RefundCoreIT |
| 每笔退款独立终态Outbox、真实Kafka重复、ConsumerInbox去重、商户提交后丢响应效果一次 | RefundCoreIT / NotificationKafkaIT / RefundMerchantDeliveryIT |
| V8到V9保留旧操作与通知原文，重复迁移、运行权限与scoped关联约束 | RefundSchemaIT及历史迁移回归 |

PR12分润和PR13争议尚未启用；不能声称已验证跨域原子联动。模糊的本地/外部退款身份合并保持冻结，交给PR16运营调查；内部审计解冻仅接受无未决退款、H=0、R重算吻合及可信来源证据。不存在退款业务状态替代资金证据，余额只经 LedgerPostingService 更新。

- [完整验证与源码摘要](verification.json)
- [最终构建输出](build-output.txt)
- [退款实际测试用例](refund-test-cases.json)
- [依赖版本](dependencies.json)
- [RED/GREEN记录](red-green.json)
- [独立审查记录](review-results.json)
- [运行说明](../../runbook/refunds.md)

本地完成开发与验证；本次未合并或推送 main。证据不包含原始 JUnit XML 的进程属性或账户密钥。
