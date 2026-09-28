# PR11 退款闭环设计

依据已冻结的 [PR11 执行范围](../../product/stripe-payment-ledger-codex-pr-prompts.md#pr11退款预占恢复通知与资金闭环)、[退款状态机](../../state-machines/refund.md) 和用户“开始做 PR11”的授权直接实施。

## 范围与选择

选择扩展现有 provider_operation、recovery_task、provider_observation、Webhook Inbox、Outbox 和 BT replay。拒绝独立退款任务表（重复恢复逻辑）与仅创建退款 API（无法恢复未知结果）。API 与 Worker 继续分离，所有 Provider 调用在事务外。

## 核心事务

按幂等命令锁、payment、refund_control、refund/operation、财务事件/账户、消息的稳定顺序处理。每笔支付一个 control，C 来自已确认 capture，R 来自成功退款，H 来自本地未明确结束的预占。NORMAL 时各值非负且 R+H<=C，使用 numeric 求和避免 bigint 溢出。创建预占、refund、通用 operation 和 recovery_task 同事务。成功只一次 H→R；明确失败/取消只一次释放 H；未知、pending、requires_action 保留 H。

外部退款以 scoped Charge 关联已确认 payment，无法唯一关联的观察等待依赖。保存可信外部事实后冻结 EXTERNAL_CONFLICT，不通过删除事实或重复计入 R/H 维持额度。终态不被旧 pending 覆盖，互相矛盾的终态保留证据并冻结调查。

## 恢复与通知

通过原协调器分派 REFUND，复用原 operation/task 租约、账户限流、原键保留窗口与查询优先策略。过期 Worker 返回只能新增观察，不能改变业务状态。已知 refund ID 用查询；未知 ID 仅在验证过的幂等窗口内原键重试，窗口外升级调查并保留 H。确定终态与 refund.succeeded/refund.failed/refund.canceledOutbox 同事务；每笔退款终态唯一通知，通过原 Kafka/Consumer Inbox/商户重试链路。

## 资金证据

生产 RefundEvidencePort 查询本地可信 refund 关联。退款扣款与失败返还各按真实 BT ID 生成独立 Journal，费用按 signed fee 原值，返还不能删除原账。业务状态不作为 BT 入账的成功门槛；BT 先到时等待退款对象关系，后续重放。FinancialStatus 依据必需 BT 的实际入账与冲突计算，不能以退款成功代替资金完成。

## 边界与验收

严格 scoped POST/GET /v1/refunds、正整数最小货币单位、同键异 payload 冲突、clientRefundKey 唯一。分润保持未启用；PR12 需对所有额度变更原子接线，争议策略端口在 PR13 实现。验证覆盖真实 PostgreSQL 屏障竞争、租约过期、外部冲突、Webhook 重复乱序、BT 先到/失败返还、消息重复效果一次、空库/升级迁移。真实 Stripe 测试显式 opt-in，未执行如实 NOT RUN。
