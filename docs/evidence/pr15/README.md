# PR15 L3 回补与三层对账验证

基线 `f0ce128bf4c7e3ebb20ba1c6e36c9c3ed4b33ec2`，分支 `codex/pr15-reconciliation`。

最终 Java21 `./mvnw -B clean verify`：**599 passed，3 skipped，0 failure/error**。单元测试 198 项、集成测试 401 项通过。默认跳过三个已有真实账户契约/只读探测测试。构建输入 SHA256 在验证前后完全相同；暂未合并或推送 main。

## 已验证行为

- 既有 recovery_task 的 L3 类型、token/version/租约所有权与真实双连接竞争；过期旧 owner 无法关联，失败事务无支付、通知或 Journal 残留。
- BT → Charge → PI → 服务端可信订单关联，metadata/同金额/无订单/金额或 API 版本冲突拒绝；BT 冲突在关联事务内重查，历史履约再次拦截冲突。
- 报告冻结后任务仍可恢复，操作观察继续追加，冻结七项结果和异常不改写；回补 GET 无伪造 CREATE_CHECKOUT 操作。
- 业务时区/DST、UTC 半开窗口午夜边界、重叠回看、分页断点/重复/晚到、pending→available 及期初 Journal 不双计。
- 真实页面链和账户级 ID 覆盖；财务检查隔离到本 run 币种/PAYMENTS 域；持久 replay cursor 跨 25 条批次推进。
- 独立期间报告 fixture 的请求/查询均在事务外；pending reportId 经临时失败或抛出读取异常后仍复用，完整结果固定相同 cutoff。
- 独立期末 900 与已导入净额 1000 比较产生 residual 100/FAIL；缺独立证据为 INCOMPLETE，未制造调整分录。
- 七项检查独立检出事件/Journal 双向遗漏、投影及平衡分录篡改、退款额度/老化、分润上限/历史、payout 本金/在途/银行/返还/费用错误。
- 缺证据不能因某项已有 FAIL 而把整体误标 COMPLETE；已确定违规不会被其他缺失证据降级。
- 版本化费用期望支持明确费用方向与舍入；缺模型为 UNMODELED_FEE，实际 signed BT/银行费用不被模型改写。
- 完整银行范围的部分到账为短款/FAIL，缺完整银行范围为 INCOMPLETE；冻结全部相关银行文件、未匹配行、分配和分录输入。
- V13→V15、历史迁移升级和重复迁移 0、运行角色最小权限、跨范围 FK 与不可变来源；并发追加证据不能越过冻结边界。

## 证据与边界

三方对账由独立人工编写的外部常量/ReportPort fixture 验证。固定示例是 opening0 + imported1000 − externalClosing900 = residual100，外部 closing 包含导入中遗漏的退款100。预期值不由生产计算函数生成。

当前 Stripe 历史报告适配器返回 `historical_report_contract_unverified`；**真实历史期间报告 NOT_RUN**，相应真实账户完整性不能宣称 PASS。银行依据均为 **SIMULATED_BANK_EVIDENCE**，真实银行完整到账核对 NOT_RUN。本期没有进行新的真实提供方付款、退款或 payout 写操作。

- [最终构建与输入摘要](verification.json)
- [最终全量输出](build-output.txt)
- [最终专项验证](quality-final-focused-green.txt)
- [实际 PR15 测试用例](test-cases.json)
- [验收覆盖](acceptance-map.json)
- [规格与质量审查](review-results.json)
- [RED/GREEN 记录](red-green.json)
- [依赖版本](dependencies.json)
- [运行说明](../../runbook/reconciliation.md)
- [状态机](../../state-machines/reconciliation.md)

只归档文本测试报告，不包含 JUnit XML 的进程属性或账户凭据。

归档文本日志仅去除了行尾空白，保留原始测试结果、错误和时间记录。
