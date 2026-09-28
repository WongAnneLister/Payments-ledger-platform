# PR10 BT 导入与资金入账验证

开发基线 `9234f637162dc269668ea614d2afdd1547c4e14f`，功能分支 `codex/pr10-bt-ingestion`。

Java21 执行 `./mvnw -B clean verify`：**389 passed，2 skipped，0 failure/error**。单元测试 181 项通过，集成测试 208 项通过。本期新增 40 项测试。两个跳过项为显式启用才运行的真实 Stripe 账户测试。

本次使用真实 PostgreSQL 16.14、受限 `payments_app` 运行连接、Fake Provider 与提供方协议 fixture；完整回归也运行此前的实际 Kafka 容器与本地商户测试。**真实 Stripe BT 导入为 NOT_RUN**。退款基础分录及依赖重放使用受控证据 fixture，生产退款业务由 PR11 接通；争议/payout 业务分别由 PR13/14 接通，独立源数据完整性由 PR15 验证。

| 验证范围 | 证据 |
|---|---|
| 分页中断/续传、缺页/错误游标、API 版本漂移、页与游标原子回滚、并发导入及窗口隔离 | FinancialIngestionIT |
| 同键金额/币种冲突、费用明细规范化、真实手续费与退款映射、依赖等待及重放 | FinancialIngestionIT / FinancialRulesTest |
| 未知 PAYMENTS 类型正负 Suspense、跨域/FX 隔离、JPY 与 DST、期初 cutoff 含等号 | FinancialIngestionIT / FinancialRulesTest |
| pending→available 子项转换、总额不变、期初未分配可用性、运行角色只读视图 | FinancialIngestionIT / FinancialSchemaIT |
| 支付查询/Webhook/BT 并发、延迟 BT、财务状态保留、原子失败回滚、逐科目独立分录扫描 | PaymentFinancialIngestionIT |
| 运行角色最小权限、证据不可改、页关联/游标不可换、完整本地事务重试 | FinancialSchemaIT / FinancialIngestionIT |
| API/Worker 角色隔离、显式启用、独立调度线程、旧拒绝窗口不阻塞新运行 | FinancialIngestionWorkerTest / PaymentFinancialIngestionIT |
| V1/V2/V4/V5/V6 历史升级，身份与通知证据保留 | 既有迁移测试的 V8 回归 |

规格审查和独立质量复审均 **PASS**。审查关闭了退款资金不应依赖业务成功、期初边界重叠、可用性维度缺失、异常刷新丢失 pending 跟踪、分页证据错关联、共享调度线程阻塞及旧窗口永久阻塞新扫描等问题。收尾回归修正了 CHARGE 大小写导致已入账覆盖遗漏，以及历史迁移 fixture 对旧 schema 的依赖。

窗口 COMPLETE 只代表该次分页链完整提交，不等于独立证明外部源数据完整。没有额外增加 PSP 投影余额，所有正式记账都经过 LedgerPostingService。

- [完整验证与 196 个源码文件摘要](verification.json)
- [最终完整构建输出](build-output.txt)
- [PR10 实际测试用例](bt-ingestion-test-cases.json)
- [执行测试的依赖版本](dependencies.json)
- [RED/GREEN 记录](red-green.json)
- [独立审查记录](review-results.json)
- [运行手册](../../runbook/bt-ingestion.md)

证据不包含携带进程属性的 JUnit XML，也不包含账户密钥。PR10 本地功能分支已完成验证，本次未合并或推送 main。
