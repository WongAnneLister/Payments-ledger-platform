# PR08 Webhook Inbox 验证

基线 `e3cac76`。本期验证原始字节验签、持久接收、异步支付观察、传输/语义去重、乱序及依赖等待。

| 验收范围 | 本期验证 | 后续边界 |
| --- | --- | --- |
| AC-V1-06/07 | 成功 capture 不被旧失败或取消通知抹掉 | 不实现退款 |
| AC-V1-11 | 原字节签名、无效签名无副作用、持久化后应答 | 本地签名不能冒充真实投递 |
| AC-V1-12 支付部分 | 同 event 重放、不同 event 同 capture、查询与事件共用规范事实 | BT/Journal 财务去重由 PR10 完成 |
| AC-V1-13 支付部分 | Charge/PI/Session 乱序、缺依赖后恢复 | 退款/争议业务依赖在 PR11/13 完成 |

证据来源分为 `LOCAL_SIGNED_FIXTURE`（本地专用 HMAC secret，真实 SDK verifier）、`POSTGRESQL_RUNTIME_ROLE`（隔离 PostgreSQL/受限应用角色）、`VERIFIED_BOUNDARY_FIXTURE`（直接构造已验签边界，用于并发/持久化测试）和 Provider 模拟/本地 SDK transport。历史真实签名与支付记录仍属于 [PR05 联调](../pr05/lifecycle/README.md)和 [PR06](../pr06/README.md)，不将其计成本次回调 HTTP 端到端实验。

Java 21 执行 `./mvnw -B clean verify`：**309 passed，2 skipped，0 failure/error**。其中单元测试 161 项通过，集成测试 148 项通过；跳过项是显式 opt-in 的真实账户测试。OpenAPI 27 paths / 58 schemas / 87 refs 检查通过，文档链接无断链。

- [完整验证与 source SHA-256](verification.json)
- [构建输出](build-output.txt)
- [回调相关测试清单](webhook-test-cases.json)
- [开发阶段 RED 记录](red-green.json)
- [独立规格与质量审查](review-results.json)

| 场景 | 测试证据 |
| --- | --- |
| 原字节验签、落库后应答、DB 故障重试、无效头/类型/范围拒绝 | StripeWebhookHttpIT（9） |
| 传输重放、不同 event 同 capture、查询/回调并发、租约过期、处理回滚 | WebhookProcessingIT（18） |
| 6 种 Session/PI/Charge 顺序、延迟本地支付、跨账户隔离、未来资源等待 | WebhookAssociationIT（11） |
| V5 升级、runtime 权限、证据不可变、PI 唯一、旧重复关联使升级回滚 | WebhookSchemaIT（2） |
| 敏感字段/扩展对象脱敏、金融数值类型、语义身份 | WebhookNormalizerTest（3） |
| 默认关闭与 Worker 无回调入口 | ApiRoleHealthIT、WorkerRoleHealthIT |

规格和质量审查均已复核通过。修复项包括并发 PI 双归属、旧 Session 关联丢失，以及创建请求拒绝晚到时错误取消已有远端 PI 的支付。

运行配置和边界见[Webhook Inbox 手册](../../runbook/webhook-inbox.md)。本期不向真实 Stripe 发起资金写操作，完整 R0 真实能力门槛仍为 BLOCKED。
