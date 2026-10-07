# 既有观测测试的时钟混用失败

本轮默认命令实际执行 100 个 seed / 10000 步并通过，整体 clean verify FAIL：220 项单元测试通过；469 项集成测试中 14 项 ObservabilityIT 返回 401，3 项真实探针跳过。完整原始运行器、case、并发、mutation、数据库快照及 Maven 输出保存在 original-artifacts.tar.gz，逐文件哈希见 manifest.json。不得将这轮作为整体 PASS。

ObservabilityIT 的应用/鉴权使用固定 2026-10-06T12:00:00Z，而 credential.not_before 使用数据库 now()-1 day。2026-10-07T12:00:00Z 以后，固定鉴权时间早于凭据有效窗口，正常请求被拒绝。将测试凭据窗口改为相同固定时钟；生产鉴权规则不变。孤立 RED/GREEN 和最终全量结果在上级报告中单独保存。
