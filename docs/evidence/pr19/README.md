# PR19 故障实验执行证据

状态：实施及验证中，尚不宣称 AC-V1-35 通过。

基线 `c600b15`；分支 `codex/pr19-faults`。设计和运行范围见 [设计](../../superpowers/specs/2026-10-09-pr19-faults-design.md)、[实施计划](../../superpowers/plans/2026-10-09-pr19-faults.md)和[运行手册](../../runbook/pr19-fault-matrix.md)。

基线：220 项单元测试 PASS。证据写入器初始 RED 是缺实现的编译失败；不能称为业务断言失败。初始运行器 RED 是缺模块导入失败。最终矩阵、计数、失败与限制将从真实运行产物更新，远程 CI 尚未运行。
