# PR12 内部分润验证

基线 `d11a3ea98d62b2b9d01b98716cf455275b4f15cb`；分支 `codex/pr12-revenue-share`。

Java 21 执行 `./mvnw -B clean verify`：**466 passed，2 skipped，0 failure/error**。单元测试 190 项、集成测试 276 项通过。真实 Stripe 账户测试需要显式启用，本次为 **NOT_RUN**；PR12 为内部账务，不提供对外打款，因此合作方真实付款为 **N/A**。

| 范围 | 测试证据 |
|---|---|
| 手算2000/1200、整数溢出边界、10000组独立除商余数模型、多合作方费率上限 | SharePolicyTest |
| 冻结快照、多合作方碎片退款、可信入账门槛、历史补计提幂等、查询隔离、不可修改历史 | RevenueShareCoreIT |
| 预占撤回、成功H转R、失败/取消/拒绝恢复、UNKNOWN保持、外部冲突冻结及审计解冻 | RevenueShareLifecycleIT |
| PostgreSQL双连接退款/计提竞争、首次计提与退款异常完整回滚、Journal独立重建、PSP不受影响 | RevenueShareLifecycleIT |
| 隔离异常BT撤回、已入账证据冲突撤回、并发退款来源关联与导入冲突闭环、BT加锁顺序一致 | RevenueShareLifecycleIT |
| V9升级V10、旧快照保留、受限运行角色、直接篡改拒绝 | RevenueShareSchemaIT及历史迁移测试 |
| Spring默认启用、可关闭、争议扩展接口配置 | RevenueShareConfigurationTest |

全部数据库场景使用真实 PostgreSQL 16.14；全量回归同时覆盖已有支付、退款、Webhook、Kafka通知及商户交付。PR12新增25个JUnit测试方法；模型生成样例不重复计入方法数。

应用默认启用分润，所有相关事务入口已接线。争议阻断接口可接入，但真实争议生命周期及胜诉恢复仍属于 **PR13，NOT_RUN**。本期无公开分润管理API，内部 scoped 查询供PR16接入。

- [完整验证与源码摘要](verification.json)
- [最终构建输出](build-output.txt)
- [实际分润测试用例](share-test-cases.json)
- [依赖版本](dependencies.json)
- [RED/GREEN记录](red-green.json)
- [独立审查记录](review-results.json)
- [运行说明](../../runbook/revenue-share.md)

本期本地开发和提交；未合并或推送 main。证据不包含原始 JUnit XML 进程属性或账户密钥。
