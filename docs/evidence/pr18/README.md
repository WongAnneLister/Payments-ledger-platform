# PR18 属性、组合序列与并发证据

本期 AC-V1-34：**PASS（本机、独立模型 + Fake Provider + PostgreSQL 16）**。基线 `8e1dc2e`，分支 `codex/pr18-properties`。新增六个独立纯模型和确定性生成/收缩工具 PR18-1，逐步驱动生产服务核对资金结果。银行来源为 `SIMULATED_BANK_EVIDENCE`。

## 最终实测

完整命令：

```bash
JAVA_HOME=/Users/anne_lister/.jdks/amazon-corretto-21.jdk/Contents/Home python3 scripts/ci/run_pr18.py --profile pr
```

| 检查 | 实际结果 |
|---|---|
| 默认生成预算 | 100 个不同 case seeds、10000 个实际步骤；maxSteps=100；baseSeed=180018 |
| 组合顺序 | 100 种不同操作顺序；无失败 seed |
| 生成序列耗时 | 1287.226 秒 |
| 完整 clean verify | PASS，Maven exit=0，1931.401 秒，输入 SHA256 无漂移 |
| Java 单元测试 | 220 项，0 failures / 0 errors / 0 skips |
| Java 集成测试 | 469 项，0 failures / 0 errors / 3 skips |
| Java 合计 | 686 项通过，3 项真实账户探针按本期 Fake 范围关闭而跳过 |
| 真实 PostgreSQL 并发 | 四场景 PASS；每场景至少两条不同 backend PID，记录锁序、SQL 超时、结果和余额 |
| 原有 Node 页面逻辑 | 35 PASS，Node v22.15.0 |
| Python 运行器回归 | 9 PASS |
| 规格、质量独立审查 | Approved；发现的问题均修复并复验 |

真实探针的名称及跳过原因见 [test-results.json](test-results.json)，均由本期 Fake 运行开关显式关闭。运行器不读取 `.env`，显式关闭三个账户探针并移除进程中的 Stripe API/签名密钥。本期不调用真实 Stripe、不使用真实资金或银行直连。

[原始运行报告](run-summary.json)保留 UTC 起止、实际环境、命令、基线 HEAD、逐输入文件 SHA256、Maven 退出码与套件结果；[构建摘要](maven-summary.txt)和[原始 Maven 日志 gzip](maven.txt.gz)保留输出。[序列报告](sequence-summary.json)、[逐类测试计数](test-results.json)及[所有 case 清单/哈希](case-manifest.json)可独立核对。

完整原始操作和每 case 的实际余额、消息计数、对账输出打包于 [generated-cases.tar.gz](generated-cases.tar.gz)，逐文件字节核验后归档；[首个 case 示例](case-example.json)可直接阅读。该压缩包保留原始 JSON 内容，不以预算乘积代替实际步骤。

构建收尾日志包含一条 fork JVM 在 `System.exit(0)` 后 30 秒未退出而自终止的[通知](shutdown-notice.json)；Maven 最终仍为 BUILD SUCCESS、exit0，全部测试计数如上。本期保留原始日志，不将该收尾现象写成已修复。

历史 RED/GREEN 的可读文本去除了行尾空格；[原始日志清单](raw-logs/manifest.json)记录逐文件原始 SHA256 及无损 gzip 副本。原始反例、运行报告与 case JSON 未修改。

## 验收覆盖

| 规则 | 本期证据 |
|---|---|
| I-01 | 每个生成操作后检查各 Journal 借贷平衡、每账户模型余额；四个竞态后复验 |
| I-02 | 精确 Journal 数、财务事件覆盖、Provider 来源集合及重复操作零重复过账 |
| I-03 | C/R/H 与模型一致；预占、释放、终态防倒退、旧租约 fencing、过期 key 不重发；600+600 竞争只有一个获准 |
| I-04 | 独立 BigInteger floor 模型；退款、争议阻断及分润重算与累计 cap 一致 |
| I-05 | 独立模型生成同 cutoff 的 PSP 期末 fixture；完整证据 PASS，缺期末证据/错误 cutoff 为 INCOMPLETE |
| I-06 | payout 本金、返还、银行分配、在途与费用；缺银行完整声明为 INCOMPLETE，完整混合结清可 PASS |
| I-07 | 每步独立余额重建及分录 scope；有效的其他组织、环境、Provider、币种输入被拒且无写入残留 |

[并发报告](concurrency-summary.json)记录 competing-refunds、same-key-refund、refund-dispute-share、payout-shared-line 四个场景。[作用域证据](scope-isolation.json)与[出款回归快照](regression-payout-mixed.json)分别保留。

## 修复、反例与失败历史

生产修复只涉及 BankMatcher 的派生外部证据判定：5700 出款由 1200 PSP 返还和 4500 银行到账共同结清时可以 MATCHED；同时每个参与结清的银行文件必须完整且覆盖预期到账日期。上述数值均为 USD 最小货币单位的隔离 fixture。

- [混合结清 RED](payout-evidence-red.txt)：原实现 WAITING_EVIDENCE，预期 MATCHED。
- [完整性 RED](bank-completeness-red.txt)：混合及全额到账在 complete=false 时不应 MATCHED；[六项回归及四竞态 GREEN](payout-concurrency-green.txt)。共享银行行的其他未分配本金不会阻塞本 payout 的完整结清。
- [模型 RED](model-red.txt) / [GREEN](model-green.txt)、运行器[规格和质量审查](runner-review.md)、整体[独立审查记录](full-review.md)。
- [收缩识别 RED](failure-identity-red.txt) / [GREEN](failure-identity-green.txt)：失败身份含异常类、项目抛出位置和规范化完整消息；最小反例记录实际回放异常。[修复后 200 步与 mutation](failure-identity-sequence-green.txt)通过。
- [测试驱动器历史失败](diagnostics/README.md)包含短幂等键、漏记 advisory lock、银行引用格式问题，均单独标为 DRIVER_FAILURE。
- [中止记录](interrupted-before-identity-fix/interruption.json)：为修复收缩识别主动中止的 13 个 case，exit130，不是完整 PASS。
- [首轮完整失败](observability-clock-failure/README.md)：100 seeds / 10000 steps PASS，但 14 项既有观测测试混用固定时钟与数据库 now() 而 401，整体 FAIL。孤立 [RED](observability-clock-red.txt)排除顺序污染；测试凭据统一固定时钟后的 [14 项 GREEN](observability-clock-green.txt)及本次完整重跑证明修复。

[mutation-sensitivity.json](mutation-sensitivity.json)为 **MUTATION_ONLY**：通过真实 LedgerPostingService 人为多写一笔平衡分润凭证，预期模型保持原样，实际捕获并从 45 步缩到 8 步。它只证明检测和收缩敏感性，不计为真实生产缺陷。所有历史失败原始产物均保留，最终 PASS 不覆盖失败历史。

## 边界与复现

[设计](../../superpowers/specs/2026-10-07-pr18-properties-design.md)、[实施计划](../../superpowers/plans/2026-10-07-pr18-properties.md)和[运行手册](../../runbook/pr18-property-verification.md)提供预算及单 case 回放入口。生成器采用有界语法：capture、争议与 payout 的基础金额固定，退款金额、合法拓扑顺序、提前 BT 分支及重复/非法操作变化；不声称穷尽所有金额/顺序。

收缩器保留依赖、删除操作，结果只在该删除规则下最小，不保证数值或全局最小。并发使用一个 JVM 中独立 API/Worker 服务对象和真实 PostgreSQL 连接，不声称部署了多个 JVM。租约放弃/重领是受控崩溃窗口模拟。

**NOT RUN**：1000 seeds / max300 扩展运行、远程 GitHub Actions 执行、真实账户探针；CI 的默认和手工扩展入口已配置，没有新增定时任务。PR19 故障矩阵与 PR20 性能/备份恢复仍待后续实施。上一轮真实账户闭环及其尾项以原证据为准，不由本期 Fake 验证替代。
