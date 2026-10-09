# PR19 故障矩阵运行手册

PR19 执行冻结 F-01–08，使用真实本地 PostgreSQL 16 / Kafka 3.9.0 容器、独立持久 Fake Provider 效果账和独立商户库。所有时钟、seed、数据和操作键记入每份实际产物；不读取 `.env`，不调用真实 Stripe 或银行。

## 重放

```bash
# Java21、Docker 必须可用。完整回归包含 PR18 默认 100 seeds / max100。
python3 scripts/ci/run_pr19.py --fault all
# 单类重放只运行所需的集成测试类；同一类可能同时生成其他故障产物。
python3 scripts/ci/run_pr19.py --fault F-06
python3 -m unittest discover -s scripts/tests -p 'test_pr19_runner.py'
```

F-01–04 共享 Webhook 故障测试类；F-06/07 共享实际 Kafka 进程故障类。运行器只对请求范围判定完整性，但不会把 extra variant 写成请求计数。全量必须有 42 份必需 variant 产物、所有对应 JUnit 类执行且无失败/跳过；缺失、非法 counts、源码运行中漂移均 FAIL。

`target/pr19/F-*.json` 保存每个实验的注入点、恢复入口、数据集、操作键、中间/最终证据、实际计数、恢复耗时与限制；`run-summary.json` 保存源码 SHA256、基线 HEAD、环境、命令和退出码。`maven.txt` 保留子进程原始输出，运行期间暂存在 target 外，避免被 clean 删除。子 JVM 标记包含 Java PID、PostgreSQL backend PID 和提交/释放/强杀时间线。测试控制文件与各 actor 日志位于 JUnit 临时目录，关键标记必须进入持久 JSON。

## CI 与来源边界

CI 以八个隔离 job 重放各类，失败仍上传完整产物。汇总 job 再检查所有必需 variant、JUnit 结果、每个 job 的源码清单/commit 一致性，缺 job 不会被写成 PASS。可对下载的同格式目录执行：

```bash
python3 scripts/ci/run_pr19.py --aggregate target/pr19-ci
```

目录布局为 `每个 job/pr19/run-summary.json`、`每个 job/pr19/F-*.json` 与 `每个 job/failsafe-reports/TEST-*.xml`。远程 CI 在真正执行前保持 NOT RUN。

强杀实验是受控本地子 JVM 中生产组件的崩溃，故障标记让窗口可确定；不能外推为生产集群可靠性保证。Kafka pause/unpause 是真实容器中断，独立商户库效果是模拟业务履约。恢复耗时只适用于记录的环境与固定样本；时钟推进的 31 秒租约不等于等待了 31 秒墙钟，更不代表外部业务最终完成承诺。资金 Journal、退款额度、分润 cap 与银行本金约束沿用既有规则。

实际执行状态及失败历史见 [PR19 证据](../evidence/pr19/README.md)。
