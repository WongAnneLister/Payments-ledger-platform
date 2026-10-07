# PR18 整体独立审查

## 规格

审查者 `pr18_runner_spec_review` 只读审查六个模型、生成/收缩、真实服务驱动、逐步不变量、scope、并发、出款证据、运行器和 CI，整体结论 **Approved**。独立复跑 Python 运行器 9 项通过；没有替代主执行者运行 Maven。

出款审查提出：不完整银行文件不能因金额相等就得到外部 MATCHED。新增混合返还/到账及全额到账两种 `complete=false` 回归，观察 2 个断言失败后加共享完整性检查；完整共享银行行只分配本 payout 的本金仍能通过，不要求整行归零。六项出款回归及四个数据库竞争场景 GREEN 见 [payout-concurrency-green.txt](payout-concurrency-green.txt)。

## 质量

审查者 `pr18_runner_quality_review` 整体审查后发现一项 P2：原收缩失败指纹只取消息第一行，未加描述的 AssertJ 消息以换行开头，使部分不同断言退化成 `java.lang.AssertionError:`；最小序列的异常字段还保存了原异常，而不是实际回放异常。

[回归 RED](failure-identity-red.txt) 实际 3 项测试、2 个断言失败、0 errors。修复为异常类、首个项目抛出位置和去 seed/UUID 噪声的完整消息；新增不同断言、同一断言位置不同失败内容、同一失败换 seed/UUID 三种回归。最小反例产物保存实际回放异常、原异常、指纹、版本和分类。[GREEN](failure-identity-green.txt) 为 13 项 PR18 单元测试全部通过。

独立复审上述源码及证据，复跑 Python 9 项与 diff 检查，整体质量结论 **Approved**。没有调用 Maven 或编辑仓库。建议未来扩大生成金额范围前进一步加固模型溢出检查；本期金额有界，不将其统计为本期资金缺陷。

运行器早期的单独审查见 [runner-review.md](runner-review.md)，不代替本次整体审查。整体 Approved 是审查结论，全量构建和默认 100 个 seed 的实际结果仍由最终运行报告单独证明。

## 全量发现的既有测试时钟边界

首轮完成默认 100 个 seed / 10000 步，但整体构建因 ObservabilityIT 14 项返回 401 而 FAIL。固定应用/鉴权时钟为 2026-10-06T12:00Z，测试凭据却用数据库 now()±1 day，在后一天的实际执行中固定时间早于 not_before。单独运行一项仍 RED，排除组合测试污染；[孤立 RED](observability-clock-red.txt)与[14 项 GREEN](observability-clock-green.txt)已保存。

最小修改只让测试凭据窗口使用同一个 NOW，不改生产鉴权。独立质量复审确认 SQL 参数、时间边界及权限约束仍然有效，整体 Approved 保持。首轮完整失败产物见[原记录](observability-clock-failure/README.md)。最终版本需要重新执行完整默认预算，不用这轮局部 GREEN 冒充全量结果。
