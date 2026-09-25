# PR07 持久操作恢复验证

基线为 `ddbc7f4`（PR06）。PR07 在同一组 provider_operation/recovery_task 上实现支付 L1/L2，查询证据与原键恢复共用现有支付事实归并路径。

## 验收范围

| 验收项 | 本期验证目标 | 边界 |
| --- | --- | --- |
| AC-V1-07 支付层 | 取消恢复与成功 capture 竞争保持资金事实 | 不实现退款 |
| AC-V1-08 支付层 | 已提交义务、未知结果、暂空查询通过 L1/L2 恢复 | 不覆盖无本地记录的 L3 外部枚举 |
| AC-V1-09 支付层 | 已验证窗口内原键恢复；窗口外不盲重发 | Fake 窗口不是 Stripe 保留承诺 |
| AC-V1-10 支付层 | 多 Worker 唯一领取、过期 token 不改控制、旧结果留证和再发现 | 退款将在 PR11 接入同一恢复表与服务 |

## 故障矩阵

以下场景使用 `OperationRecoveryIT`；每个用例建立独立数据库及 Fake 状态。远端计数来自 Fake 的持久效果记录。

| 故障或竞争 | 验证结果 | 独立远端效果断言 |
| --- | --- | --- |
| 领取后、调用前中断 | 租约过期后原命令恢复 | 1 个 Session |
| 远端受理、响应丢失 | 验证窗口内原键恢复 | 1 个 Session |
| 两个 Worker 同时争抢 | 仅一个重新领取并执行 | 1 个 Session，累计领取 2 次 |
| 旧 Worker 超租约返回 | 留存观察，新 Worker 按锚点查询 | 1 个 Session，仅 1 次受理 |
| 新 token 已完成、旧 token 再返回 | 控制版本不被改写，保留两次观察 | 1 个 Session |
| 查询成功、本地回写前中断 | 下个租约重查并恢复成功 | 1 个 Session、1 次 capture、0 个 Journal |
| 暂空查询后成功 | 暂空保留 UNKNOWN，后续恢复 SUCCEEDED | 1 个 Session、1 次 capture |
| 无锚点、窗口过期但远端已扣款 | 持久 ESCALATED，L2 也不重发 | 1 个 Session、1 次 capture、1 次原始请求 |
| 未验证窗口且缺锚点 | 持久 ESCALATED、保留首次发送时间 | 0 次 Provider 请求 |
| 回写短事务内时钟跨越租约边界 | 以持锁授权点完成原子回写，不拆开操作与任务状态 | 1 个 Session |
| 取消远端已完成、回写丢失 | 重查原 Session 并收敛 CANCELED | 无额外受理、0 次 capture |
| 取消恢复前远端已 capture | 保留 SUCCEEDED、取消操作收敛 | 无额外受理、1 次 capture |
| 同一操作观察出现冲突锚点 | 保留 CONFLICT 并升级，不能随意选择 | fixture 主动制造 2 个 Session、0 次 capture |
| 多实例账户限流 | 共用数据库批次预算 | 未到间隔只建 1 个，后续共 2 个 Session |
| 事务内误调用恢复入口 | 拒绝执行远端调用 | 0 次 Provider 请求 |

另有 L2 不抢有效租约、不重置原始窗口，runtime role 禁改窗口/资源关系，合法等待不被老化升级，以及已知锚点优先查询的断言。HTTP 故障用例从真实业务入口注入“远端已创建并 capture、响应丢失”，验证 ESCALATED 对本应用可见、对其他应用不可见，重放后仍只有 1 个 Session 和 1 次 capture。

Stripe SDK transport fixture 另外验证 PI 内部分页、跨端口查询、嵌套作用域共用实际 HTTP 预算；第 33 次请求不会发出，耗尽后不执行取消，关闭批次后清理线程作用域。这些测试不访问真实 Stripe。

## 证据规则

数据库并发及崩溃测试使用隔离 PostgreSQL、受限 runtime role 和独立持久 Fake。Session 创建数、capture 效果数和本地资源数分别核对。HTTP 测试通过认证业务入口观察恢复进度与范围隔离。真实 Stripe 不在本 PR 注入未知创建/重复资金命令；历史真实证据见 [PR05 联调](../pr05/lifecycle/README.md)和 [PR06 业务 API 验证](../pr06/README.md)。

## 最终验证结果

`JAVA_HOME=<Corretto21> ./mvnw -B verify` 退出码 0：**264 项通过、2 项跳过、0 失败、0 错误**。其中单元测试 158 项通过，集成测试 106 项通过；2 项真实账户 opt-in 测试未启用，状态为 NOT RUN。原 27 项支付核心测试通过共享 fixture 保留，恢复测试不继承重复执行这些用例。

本期新增 31 项：23 个恢复集成用例、3 个恢复策略单测、1 个 V4→V5 升级用例、2 个 HTTP 恢复用例、2 个 Stripe HTTP 分页预算用例。浏览器另外验证 7 个交互断言，包含升级提示与阻止换键重付。OpenAPI、文档链接、diff 检查通过；独立规格审查与质量审查均通过，所提问题已修复。

- [构建、计数与源码 SHA-256](verification.json)
- [完整构建输出](build-output.txt)
- [新增恢复与预算测试清单](recovery-test-cases.json)
- [独立审查结果](review.json)
- [浏览器 fixture 验证](browser/verification.json)
- 文本测试报告保存于 `test-reports/`；未归档可能包含环境配置的 XML。

## 未实现范围

PR08 Inbox、PR09 通知、PR10 BT 导入/记账、PR11 退款业务、PR15 L3/人工核实、PR16 运营处理仍属后续。完整 R0 真实门槛仍为 BLOCKED；本地可控时钟验证不宣称生产 SLA。

运行与人工调查边界见[恢复运行手册](../../runbook/operation-recovery.md)。
