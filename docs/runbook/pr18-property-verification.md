# PR18 属性与并发验证

本套件使用独立整数模型、现有生产服务和一次性 PostgreSQL 16。Provider 为 Fake，银行输入明确为 `SIMULATED_BANK_EVIDENCE`。运行器不读取 `.env`，并关闭三个真实账户契约探针。

## 运行与预算

要求 Java 21、Python 3 和可用 Docker。先确认 `JAVA_HOME` 指向 Java 21，然后在仓库目录执行：

```bash
python3 scripts/ci/run_pr18.py --profile pr
```

默认请求 100 个 case seeds、每序列最多 100 步，执行 `./mvnw -B clean verify`。扩展配置：

```bash
python3 scripts/ci/run_pr18.py --profile nightly
```

扩展预算为 1000 个 case seeds、每序列最多 300 步。GitHub Actions 的手工 `workflow_dispatch` 提供 `pr18_nightly` 开关；仓库未自行添加定时调度。代码中的入口存在不代表远程 CI 或扩展配置已经执行。

自定义可复现起点及预算：

```bash
python3 scripts/ci/run_pr18.py --profile pr --seed 180018 --seeds 100 --max-steps 100
python3 -m unittest discover -s scripts/tests -p 'test_pr18_runner.py'
```

运行器拒绝 seed/count/step 越界，要求实际完成全部请求的 case seeds，数量不足、无并发证据或输入执行期间变化均返回非零。`maxSteps` 是上限，实际步数由报告给出；不能将上限乘积当作执行数量。操作生成、回放与缩小使用固定版本，并保留每个实际 case seed。

## 证据位置

`target/pr18/run-summary.json` 保存命令、起止时间、耗时、环境、输入文件 SHA256、Maven 退出码和两个套件的实际结果。`maven.txt` 保存本次输出；为避免 Maven clean 删除正在写入的日志，运行器先将日志写入临时目录，再归档到 target。

`sequence-summary.json` 记录实际 seeds、步数和各操作数量；`concurrency-summary.json` 记录独立连接的 PostgreSQL backend PID、屏障竞态及结果。生成序列失败时保存原始操作、失败 seed、最小反例和实际数据库/Provider/消息快照；人工 mutation 敏感性与真实实现缺陷分开记录。CI 无论成功或失败均上传 `target/pr18/**` 以及 Surefire/Failsafe 报告。

固定 seed 只是复现入口，完整命令和生成器版本必须一同保留。不要删除失败样本后把后续通过当作没有失败，也不要把驱动器构建错误统计成资金缺陷。

直接回放报告中的一个 case seed（此模式不代表完成默认 100 个 seed 的预算）：

```bash
./mvnw -B -Dtest=PropertyModelsTest,SequenceGeneratorTest -Dit.test=Pr18SequenceIT -Dpr18.caseSeed=815872335101841148 -Dpr18.maxSteps=100 verify
```

运行器的默认入口会显式关闭真实账户探针；直接使用 Maven 时也应保持这些开关关闭。生成器 PR18-1 对独立生命周期节点作随机拓扑排序，随机选择提前到达的 BT 分支、退款金额和重复/非法操作。它覆盖有界语法，capture、争议与 payout 本金采用固定 fixture，不声称穷尽所有金额和顺序。收缩器保留依赖并逐项删除操作，结果只在该删除规则下最小；没有数值收缩或全局最小保证。

## 判定边界

每个本地事务核对借贷平衡、事件唯一性与入账覆盖、退款额度、分润上限、隔离和余额重建。PSP/银行外部守恒只在证据完整且 cutoff 一致后检查；缺证据必须保持 INCOMPLETE。串行随机序列与并发证据分别报告。并发中的独立服务对象和数据库连接不等同于部署了多个 JVM；进程崩溃故障矩阵由 PR19 扩展。

本期结果和明确未运行范围见 [PR18 证据](../evidence/pr18/README.md)。测试中出现真实缺陷时，先保留原始失败，再做最小生产修复并增加回归，不能更改不变量或预期模型来迎合实现。
