# 驱动器失败记录

这些失败保留原始操作、收缩结果和数据库/Provider/消息快照，均为隔离 Fake 数据。

- `failure-815872335101841148-*`：DRIVER_FAILURE，测试 API 幂等键过短，未满足既有最小长度。修正为 `pr18-api-` 前缀。不是资金实现缺陷。
- `payout-reference-driver-failure.json`：DRIVER_FAILURE，fixture 中 payout 引用带连字符，不满足既有银行导入格式。修正为下划线。失败发生在竞争分配前，不是并发资金缺陷。
- `pilot-lock-trace.txt`：DRIVER_FAILURE，同键回放使用 advisory lock，测试追踪器只记录行锁，误报追踪为空。补记 advisory lock 后通过。
- `pilot-reference-format.txt`：上述银行引用格式问题的 Maven 输出；该轮 2 个 seed / 120 步、mutation 和 scope 测试已通过，但整体构建因驱动器输入错误失败，不能当作全量 GREEN。

生产出款分类缺陷的 RED 见上级目录的 `payout-evidence-red.txt`、`bank-completeness-red.txt`；人工 mutation 另标 MUTATION_ONLY。最终正常运行产物不覆盖这些历史失败。
