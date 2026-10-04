# PR16 运营 API、独立审批与审计验证

基线 `e0730409cab079822e218d846c483b3ae44811b4`，分支 `codex/pr16-ops`。最终 Java21 `clean verify`：**634 passed，3 skipped，0 failure/error**，其中单元测试 198 项、集成测试 436 项通过。构建输入 SHA-256 在运行前后保持相同。

## 已验证行为

- 12 个领域 GET 与 6 个 POST 入口；可信 OpsContext 范围、五类角色、合作方仅自身分润行、跨组织/环境资源不可见。
- 原生前缀 ID 和 UUID、查询字段、稳定分页、历史截止余额、业务与资金状态独立；完整性不足保留 UNKNOWN/PENDING_EVIDENCE/INCOMPLETE。
- 同键重放与改变内容冲突；对账仅持久化运行请求，银行文件规范摘要及旧格式转换、重复文件不重新分配本金。
- 调整申请不入账；独立复核、禁止自审、并发重复批准至多一张凭证，过账或审计失败整体回滚。
- 当前目标批准的人工调整凭证进入付款/退款/争议/payout 详情。证据摘要不含原始敏感 payload。
- 差错解决要求因果相关证据；原始报告不可改写。最新动作按锁内生成序号排序，旧事务后来执行也正确更新处理状态。
- 恢复重试遵守既有租约、终态和过期 UNKNOWN 限制；来源任务与 Worker 使用任务→BT 锁顺序，双连接竞争已验证。
- 有上限的详情集合获取最新 100 条并按时间展示，明确 traceCompleteness/truncatedSections；凭证截断不会误标完整的分润行。
- 所有写命令、重放与拒绝审计；成功审计与业务同事务，失败独立记录；认证前拒绝为 ANONYMOUS，不保存凭据或原始理由。
- V15→V16 升级、重复迁移、最小运行角色权限、不可变动作/审计、范围外目标和最终审批改写拒绝。

## 实际失败与验证边界

初始 RED 包含缺表错误，与行为断言失败分别记录。审查问题均先通过回归复现再修复。首次质量修复构建发现分润查询引用不存在的 created_at 字段；修正为既有 updated_at 后，相关查询在后续验证通过。

较早的一次全量构建中，既有取消付款用例失败。该运行出现多次长时间中断和 Hikari thread starvation/clock leap 提示；未修改该流程，单独重跑 PaymentHttpIT 的 8 项测试全部通过，最终全量 clean verify 也通过。失败日志保留，不将中断原因当成已证实事实。最终构建使用命令期间有效的 caffeinate 防止空闲睡眠，没有更改系统电源配置。

本期验证使用本地 PostgreSQL/HTTP 和受控 fixture。银行仍是 **SIMULATED_BANK_EVIDENCE**；真实银行和 Stripe 历史期间报告 **NOT_RUN**。没有新增真实 Stripe 资金写操作，三个既有真实账户测试默认跳过。本目录不把模拟通过当作真实账户契约通过。

## 证据

- [最终命令、统计与输入 SHA-256](verification.json)
- [最终全量原始输出](build-output.txt)
- [专项最终 GREEN](quality-green.txt)
- [实际测试用例与跳过项](test-cases.json)
- [验收覆盖 AC-V1-01/31](acceptance-map.json)
- [规格与质量审查](review-results.json)
- [实际 RED/GREEN 与诊断记录](red-green.json)
- [先前全量失败](pre-quality-full-failure.txt)
- [取消付款独立重跑](payment-http-diagnostic.txt)
- [交付检查](delivery-checks.txt)
- [依赖版本](dependencies.json)
- [运营手册](../../runbook/ops.md)
- [调整状态机](../../state-machines/adjustments.md)

只归档文本报告，不包含 JUnit XML 的进程属性、环境或账户密钥。文本日志只去除行尾空白，保留实际输出与时间。此阶段仅本地提交，main 和远端保持原状。
