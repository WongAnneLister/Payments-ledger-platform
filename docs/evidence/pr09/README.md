# PR09 商户通知验证

基线 main `b2765625043a6f4c8533b4b64a397ca11e2d5755`。范围为 PR09 / FR-PAY-04 / AC-V1-14、AC-V1-15 的支付部分。

Java21 执行 `./mvnw -B clean verify`：**349 passed，2 skipped，0 failure/error**。单元测试 170 项通过，集成测试 179 项通过；两个跳过项是显式启用才会运行的真实 Stripe 账户测试。本期新增 40 项测试。

使用实际 PostgreSQL16.14、Kafka3.9.0 和 loopback HTTP 商户；商户履约记录保存在独立 PostgreSQL 容器。真实 Stripe 和外部商户投递均为 **NOT_RUN**，不能把本地一次履约效果宣称为对任意第三方的 exactly-once 保证。完整 R0 真实能力门槛仍为 BLOCKED。

| 场景 | 测试证据 |
|---|---|
| 同步/查询/恢复/Webhook 原子 Outbox、插入失败回滚、重复观察、partial→full capture | PaymentOutboxIT（7） |
| 发送后未标记、消费事务失败/提交后未确认、并发消费者、租约隔离、HELD/DEAD/退避/指标、数据库权限边界 | NotificationPipelineIT（16） |
| V6 升级无历史事件/自动资格，生产默认权限收口 | NotificationMigrationIT（1） |
| 真实 broker 暂停/恢复、同消费组关闭重启与 offset 证据、实际 Spring listener 数据库失败不确认 | NotificationKafkaIT（3） |
| 商户提交后丢响应、不同事件同业务键、无效签名/身份复用、禁止事务内 HTTP/重定向 | MerchantDeliveryIT（3） |
| 完整 Spring Boot Worker 启用后真实 Kafka 分区分配与生产组件装配 | NotificationWorkerWiringIT（1） |
| 地址与 DNS、HMAC、慢速响应截止时间、HTTP 状态码和受控配置 | 4 个单元测试类（9） |

规格审查与独立质量复审均 **PASS**。关闭的问题包括内部 Kafka 契约缺口、部分扣款提前冻结成功事件、资格函数可被运行角色挂载到伪触发器，以及运行角色直接插入不一致 Outbox 载荷。最后一项通过统一受控 writer、固定 search_path、撤销继承的默认表权限和真实 PostgreSQL 回归修复。

OpenAPI 检查：27 paths / 58 schemas / 87 refs 全部有效；相对文档链接与 `git diff --check` 通过。归档不包含带进程属性的 JUnit XML，仅保留文本测试报告。

- [完整验证与 177 个源码文件 SHA-256](verification.json)
- [完整构建输出](build-output.txt)
- [通知测试用例](notification-test-cases.json)
- [实际依赖版本](dependencies.json)
- [开发阶段 RED/GREEN 记录](red-green.json)
- [独立规格与质量审查](review-results.json)

运行和配置见[运行手册](../../runbook/merchant-notifications.md)及[消息与通知契约](../../contracts/merchant-notifications.md)。后续 Journal、退款业务及 L3 回补分别由 PR10、PR11、PR15 接入。本次保留 `codex/pr09-notifications` 功能分支，main 未变更。
