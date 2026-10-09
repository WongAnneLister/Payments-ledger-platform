# PR19 故障实验执行证据

AC-V1-35：**PASS（本机隔离环境，42 个实际故障实验）**。实现提交 `97e74f3c370d912d405d4a7a171e477106b97d08`；分支 `codex/pr19-faults`，基线 `c600b15`。使用生产组件、真实 PostgreSQL/Kafka 容器、持久 Fake Provider 和独立模拟商户效果库；本期未改变生产业务规则。

## 最终实测

完整重放：`python3 scripts/ci/run_pr19.py --fault all`，Java 21 / Docker 可用时执行。完整报告记录 UTC、实际环境/版本、固定 seed、数据集、操作键、注入/恢复步骤及逐输入文件 SHA256。

| 检查 | 实际结果 |
|---|---|
| 完整 clean verify | PASS；Maven exit 0；2055.506 秒；运行输入无漂移 |
| Java 单元测试 | 225 项；0 失败、0 错误、0 跳过 |
| Java 集成测试 | 511 项；0 失败、0 错误、3 跳过 |
| Java 合计 | 733 通过；3 项真实账户探针显式关闭而跳过 |
| PR19 | 42/42 PASS，0 跳过；全部必需 variant 和 JUnit 用例均严格校验 |
| PR18 默认回归 | 100 个种子 / 10000 个实际步骤；四个 PostgreSQL 竞态 PASS |
| Node 页面逻辑 | 35 PASS，Node v22.15.0，[原始日志](history/node-final.txt.gz) |
| Python 运行器 | 27 PASS（PR19 18 + PR18 9），[原始日志](history/python-final.txt.gz) |
| 独立审查 | 全范围规格及代码质量 Approved，见[审查记录](review.md) |

[原始运行报告](run-summary.json)、[逐类测试及跳过原因](test-results.json)、[42 项实验索引与计数](experiment-index.json)、[构建摘要](maven-summary.txt)、[原始构建日志](maven.txt.gz)。完整 [原始产物压缩包](raw-artifacts.tar.gz) 包含 PR19 的逐项 JSON/子 JVM 日志、PR18 的全部 case/摘要、Surefire/Failsafe 原始报告；[清单](artifact-manifest.json)逐文件核验原始字节及压缩包 SHA256。[提交溯源](commit-provenance.json)确认全部 345 个运行输入与实现提交 Git blob 完全一致。

## 八类矩阵

下表耗时为每个固定样本从恢复动作开始到断言完成的实测范围；单位为墙钟毫秒，不是外部服务 SLA。每个具体窗口的注入、预期、中间/最终资金与消息证据在原始 JSON 中。

| 类别 | 实际覆盖 | 结果 | 恢复耗时范围 |
|---|---|---|---|
| F-01 | 丢失回调 / L1、L2、可信订单 L3、无订单观察 | 4 PASS | 138–449 ms |
| F-02 | 重复 event、重复 Charge/BT、并发同 BT | 3 PASS | 308–403 ms |
| F-03 | 退款/争议 BT 提前、旧失败、payout 返还提前 | 4 PASS | 114–328 ms |
| F-04 | 字节篡改、签名/secret/时间错误、持久化失败 | 6 PASS | 16–37 ms |
| F-05 | 付款与退款发送前失败、受理后超时、键过期 | 6 PASS | 243–991 ms |
| F-06 | 事务、远端、Inbox、Outbox、消费 ack 崩溃及旧租约 | 9 PASS | 558–6074 ms |
| F-07 | 实际 Kafka 中断恢复、重复发布、提交后未 ack | 3 PASS | 307–6164 ms |
| F-08 | 双 JVM 付款、退款、分润、争议、银行及 BT 竞争 | 7 PASS | 138–403 ms |

F-06 的崩溃窗口实际强杀子 JVM，记录 PID、退出状态和数据库提交轨迹；旧 lease 是明确标注的受控延迟返回样本。F-08 每项以两个独立 JVM 和 PostgreSQL backend PID 在屏障后竞争，并独立重建余额；拒绝方保留实际 rollback 轨迹。F-07 实际 pause/unpause Kafka，覆盖发布成功未标记、消费提交未 ack 和商户响应丢失重试，独立商户效果仍为一次。运行器不会用模拟字段或一个用例替代这些完整证据。

## 失败历史和限制

所有先前失败均保留：[历史分类与 SHA256](history/manifest.json)、[最终 focused 运行清单](history/focused-final-manifest.json)。初始缺实现编译/导入 RED 只证明实现未存在；Docker socket 受沙箱阻断标为 ENVIRONMENT_BLOCKED。Kafka 首轮因测试准备错误选到早期通知而失败，原始五份 FAIL 见[驱动器诊断](diagnostics/kafka-initial-driver-failure.json)及其[原始产物](diagnostics/kafka-initial-driver-failure.tar.gz)。Webhook 首轮两个 F-03 样本使用不合规 ID，修正 fixture 后重跑；未放宽生产校验。运行器完整性/结构问题保留实际 RED/GREEN；线程清理问题通过审查发现并修正，受影响故障测试重跑通过。两者均不计为生产资金缺陷。

恢复依赖本机固定数据、Fake 时钟与受控租约推进；31 秒租约推进不代表经过 31 秒墙钟。120 秒目标只适用于具备领取资格的本地任务。受控子进程和容器实验不能推出生产集群可用性或真实 Stripe 最终完成承诺。计数零值只表示该实验实际未触发该类效果，详见注入点和原始快照。

**NOT RUN**：远程 GitHub Actions 的八 job 真正执行、PR18 1000/300 扩展预算、真实 Stripe/银行探针，以及 PR20 性能与备份恢复。CI 八类重放和严格汇总已配置；Python 汇总回归使用合成报告，只证明校验逻辑。三项真实探针按本轮 Fake 范围关闭，不覆盖此前真实账户闭环证据。

[设计](../../superpowers/specs/2026-10-09-pr19-faults-design.md)、[实施计划](../../superpowers/plans/2026-10-09-pr19-faults.md)、[运行手册](../../runbook/pr19-fault-matrix.md)提供重放及来源边界。
