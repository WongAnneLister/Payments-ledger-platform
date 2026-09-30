# PR13 争议闭环验证

基线 `8450d17d25386dc03199c89ba77253999fb5a10d`；分支 `codex/pr13-disputes`。

Java21 执行 `./mvnw -B clean verify`：**496 passed，2 skipped，0 failure/error**。单元测试 195 项、集成测试 301 项通过。两个真实 Stripe 账户契约测试默认需显式启用，本次跳过。**真实 Stripe 争议实验 NOT_RUN；争议生命周期证据为 fixture-only**，使用真实 PostgreSQL 16.14 和受限运行角色。

| 范围 | 实际测试证据 |
|---|---|
| inquiry仅阻断/撤回，无PSP扣款；deadline显示；普通退款拒绝 | DisputeLifecycleIT |
| 扣款、实际费用、won缺返还不恢复、lost只重分类、late win | DisputeLifecycleIT |
| 应收1000+损失2000+返还3500分成1000/2000/500；超额进SUSPENSE并保持阻断 | DisputeAmountsTest / DisputeLifecycleIT |
| BT先到、返还先到、Webhook旧租约、重复事件、多个争议最后阻断清除 | DisputeCoreIT / DisputeLifecycleIT |
| 查询恢复和两个Worker真实调用路径、失败来源轮转、网络调用在事务外 | DisputeLifecycleIT |
| PostgreSQL双连接退款/争议竞争，share/BT/history/ACK异常整笔回滚，独立Entry重建 | DisputeLifecycleIT |
| 已恢复分润遇到新资金或证据矛盾再次阻断，账本科目全局顺序，posted BT关联不可改 | DisputeLifecycleIT |
| 跨应用/币种查询隔离，原始矛盾币种留证、后补可信归属，直接计数篡改拒绝 | DisputeCoreIT / DisputeLifecycleIT |
| V10升级V11、重复迁移、scoped约束、最小数据库权限 | DisputeSchemaIT及历史迁移回归 |
| 原生du_ ID识别、只保留截止时间和BT ID，扩展对象隐私字段丢弃 | StripeFinancialReadAdapterTest / WebhookNormalizerTest |

手算本金案例：收款10000减手续费300，PSP9700。争议扣款本金3000/费用1500后PSP5200；败诉只把应收3000转损失，PSP仍5200；返还本金3000后PSP8200，费用1500保留。分润2000→0→2000仅在全部证据完备且没有其他阻断时成立。单独费用返还500的fixture使PSP8700、费用净额1000。

本期没有向Stripe提交抗辩材料或发起新的真实资金写操作。内部 scoped 查询为PR16准备。外部退款/争议重叠、超额返还与新终态矛盾保留调查阻断。

- [验证结果和源码摘要](verification.json)
- [最终构建输出](build-output.txt)
- [争议实际测试用例](dispute-test-cases.json)
- [依赖版本](dependencies.json)
- [RED/GREEN记录](red-green.json)
- [规格及质量审查](review-results.json)
- [运行说明](../../runbook/disputes.md)

本期本地提交；未合并或推送 main。证据不含原始JUnit XML进程属性或账户凭据。
