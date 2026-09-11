# 架构决策记录（ADR）索引

版本：PR00 · 状态：以下决策在 PR00 冻结，未实施

上游：[PRD](../product/payment-platform-prd.md) · [需求映射](../spec/requirements-map.md) · [OpenAPI](../contracts/openapi.yaml) · [七项不变量](../ledger/invariants.md)

## 决策清单

| ADR | 标题 | 状态 | 主要影响 |
|---|---|---|---|
| [0001](0001-modular-monolith-api-worker.md) | 模块化单体与 API/Worker 运行角色 | Accepted | PR01 工程骨架 |
| [0002](0002-money-precision-and-time.md) | 精确金额与时间/时区处理 | Accepted | PR02、PR03、PR10、PR15 |
| [0003](0003-provider-unknown-result.md) | Provider 未知结果的处理 | Accepted | PR04、PR07、PR11 |
| [0004](0004-immutable-ledger.md) | 不可变账本与一次性受控过账 | Accepted | PR03、PR10～PR16 |
| [0005](0005-provider-operation-association.md) | `provider_operation` 关联方式 | Accepted | PR06、PR11 |
| [0006](0006-authentication-and-tenant-boundary.md) | 认证与租户边界 | Accepted | PR02、PR08、PR16 |
| [0007](0007-lock-order-and-isolation.md) | 锁顺序与隔离级别 | Accepted | PR03、PR11～PR14 |
| [0008](0008-opening-balance-rebuild.md) | 余额重建口径 | Accepted | PR10、PR15 |

## 格式约定

每份 ADR 包含：**背景 → 决策 → 被否方案 → 后果 → 验证归属**。

- "被否方案"必须写明**为什么否**，不能只列出选项。
- "验证归属"指出哪个 PR、哪条 AC-V1 会实际检验该决策。
- 决策变更需新增 ADR 并将旧 ADR 标为 `Superseded by ADR-XXXX`，**不原地改写已冻结决策**。
