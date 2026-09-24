# PR06 业务订单与支付 API 验证

PR06 将可信身份、受控价格和 Provider 端口接入持久支付流程。主要入口为创建、查询、取消，以及 `/payment-test.html` 测试页面。付款事实与记账独立；本 PR 不实现退款业务、Webhook Inbox、通知或正式支付入账。

## 验收对应

| 验收项 | 验证内容 | 来源与边界 |
| --- | --- | --- |
| AC-V1-03 | 权威单价×数量、精确 minor units、溢出拒绝、不可变价格/政策 JSON 快照 | 真实 PostgreSQL / runtime role；政策后续变更不改变旧订单 |
| AC-V1-04 | 100 个并发逻辑命令，同 API 键只一订单/支付/操作/Session；独立 orderKey 唯一性；异 payload 冲突 | PostgreSQL 多连接 + 明确标注 Fake；不是 Stripe 压测 |
| AC-V1-05 支付 API 层 | 创建会话不扣款，返回托管入口、资源 ID；查询和重放同一资源 | 本地 HTTP 集成与单独 opt-in Stripe 证据分别记录 |
| AC-V1-06 支付层 | 多 Charge attempts，失败 attempt 不抹掉成功 capture；成功与账务状态分离 | Fake 场景，不补宣称真实拒绝/认证卡已测 |
| AC-V1-07 支付层 | 先持久取消意图；仅 open 且无受理资金事实时 expire；成功竞争及已有冲突保留 | PostgreSQL + Fake，真实 API 未付款 Session 的过期单独记录 |
| F-06 持久义务 | 提交后执行前中断、服务重建继续 PREPARED；SENDING/UNKNOWN 不盲目重发 | 不是 PR07 的完整租约回收/恢复 SLA |
| I-07 范围与关联 | 可信组织/环境/应用/账户/币种；复合外键；账户配置匹配；外部 Charge 不重绑定 | 隔离数据库，非跨真实 Stripe 账户实验 |
| 升级与权限 | 空库及 V3→V4；runtime 不能更新订单快照/历史观察；退款关联预留但禁止使用 | 追加 V4 迁移，未改历史迁移 |

## 正确性边界

Provider 调用发生在数据库事务外。每个操作有持久稳定键、原始 command、绝对 Checkout 到期时间、领取 token 和版本；过期或失效执行者只能保留观察，不覆盖当前控制状态。支付、操作、执行任务在同一短事务写入，外部响应只存受控字段与脱敏 evidence。

成功 capture 不能因迟到查询、失败 attempt、取消或退款而抹掉。已有 CONFLICT 不由普通取消自动清除。明确拒绝与网络未知结果分别保留，阻塞账户任务不使队列中的其他可执行账户永久饥饿。历史资金观察中的缺失金额保留缺失，不用 0 替代。

网页验证使用隔离 Chrome 和明确的 HTTP fixture，来源为 `LOCAL_BROWSER_WITH_HTTP_FIXTURE`。验证金额拒绝可修正、网络响应丢失后刷新保留同一请求、应用凭据不进入存储、只有已知结果提供托管跳转。它不替代真实 Stripe 证据。

## 尚未覆盖

- UNKNOWN/SENDING 的自动查询恢复、租约重领、保留窗口策略与 L1/L2（PR07）。
- 业务 Webhook Inbox/去重、异步规范观察与通知（PR08/09）。
- BT 导入、正式账本入账、退款预占与分润计提（PR10–12）。
- PR05 仍未完成的真实拒绝/认证/多 attempt、取消竞争、退款失败、BT 分页、争议、payout 和历史报告等能力。

R0 的完整真实能力门槛仍为 **BLOCKED**；局部成功不等于全部 AC 通过。PR05 的真实付款/退款/6 个真实回调证据见[历史联调记录](../pr05/lifecycle/README.md)。配置及运行方式见[业务支付手册](../../runbook/business-payments.md)。

## 真实账户结果

2026-09-25（北京时间），通过一次性 PostgreSQL 数据库中的可信应用凭据，向已绑定 Stripe **test** 账户执行了完整业务 HTTP 链路。USD 100 minor units（1 美元）的未付款 Checkout 创建返回 **201**，查询 **200**，同键重放 **200** 且保持同一支付，取消 **200** 并最终确认为 **CANCELED**。返回了真实托管 Checkout URL，但未提交银行卡、未扣款。

该会话已通过业务取消接口关闭。没有把本次未付款验证计入真实付款/退款验收；完整收银台银行卡交互仍见 PR05 历史记录。

- [脱敏真实账户证据](real/298d1f72-7a6d-4e48-8e3a-d3091d905da2.json)
- [真实契约测试报告](real/PaymentRealContractIT.txt)

实际命令为在私有进程环境加载 Stripe test 绑定后运行 `JAVA_HOME=<Corretto21> PR06_STRIPE_CONTRACT=true ./mvnw -B -Dtest=PaymentRequestsTest,PaymentControllerTest -Dit.test=PaymentRealContractIT verify`，退出码 **0**。归档中只含账户与资源引用的 SHA-256，不包含私钥、应用凭据、完整 Checkout URL 或本地恢复文件。

## 全量回归结果

`JAVA_HOME=<Corretto21> ./mvnw -B verify` 最终退出码 **0**，`BUILD SUCCESS`：单元测试 **153/153** 通过；集成测试 **80** 通过、**2** 个真实账户 opt-in 测试默认跳过，无失败、无错误。PR06 的真实测试已在上面的独立执行中通过，不能把默认跳过当成实测。

PR06 专项包括 27 项核心数据库/并发测试、6 项真实 HTTP 集成测试、1 项 V3→V4 升级与权限测试、19 项请求/ID/Controller 单测。全套回归也保留了既有身份、账本、模拟器、Stripe 适配器与 API/Worker 启动检查。新增 V4 后，旧空库/V2 升级数量断言和无数据库启动测试配置已同步更新。

- [完整构建输出](build-output.txt)
- [验证汇总与源码 SHA-256](verification.json)
- [文本测试报告](test-reports/)
- [浏览器 fixture 验证](browser/verification.json)

核心及 HTTP 边界均完成规格和独立质量审查，发现的问题已有回归测试并修复。文档/OpenAPI 检查、差异空白检查和配置密钥值扫描的结果记录于验证汇总。
