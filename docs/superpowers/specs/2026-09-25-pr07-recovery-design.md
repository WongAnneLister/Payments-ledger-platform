# PR07 通用操作恢复与支付 L1/L2

基线：main `ddbc7f4`。用户明确要求执行已冻结的 PR07；产品范围以 PR07 提示词、provider-operation 状态机和 ADR-0007 为准。本设计细化既有方案，不引入新的业务范围。

采用 PostgreSQL 持久任务 + 短事务租约 + 事务外权威查询。没有选择内存定时器作为唯一任务来源（崩溃会丢义务），也不新增消息中间件队列（PR09 才接入）。沿用 provider_operation/recovery_task，V5 追加恢复字段、调度及范围约束，不重写 V1–V4。

## 控制与查询

OperationLeaseService 原子领取操作与任务，使用注入 Clock、唯一执行 token、版本和到期时间。锁序为 payment → operation → recovery_task；账户限流锁的顺序必须统一，不能与业务锁形成逆序。旧执行者返回永远保存受控 observation，只有当前有效租约可以更新控制结果。任务状态和操作/支付变更在同一事务提交。发生本地提交失败不在事务重试中重新调用 Provider。

ProviderQueryService 复用 PR06 完整 Session → PI → Charge 权威查询与范围/金额核对，保留成功 capture 和已有 CONFLICT。OperationRecoveryCoordinator 先找支付/操作的外部 ID，再找同 scope、同 operation 的持久观察锚点；锚点必须经过当前权威查询验证后才能关联支付。没有可信锚点时，不把 NOT_FOUND/TRANSIENT_EMPTY/超时当作没有远端效果。

PREPARED 可证明尚未发送，可以原命令执行。UNKNOWN/过期 SENDING 在已验证、配置明确且未到期的幂等保留窗口内才允许原 key/原 command 重试，绝不换 key/重新生成 expiresAt。保留窗口起点不可被重试更新。真实 Stripe 的保留窗口目前未完成本项目验证，默认关闭未知创建重试；Fake 只采用显式 fixture 窗口。过期/未验证且无锚点时变为 ESCALATED，支付保留 UNKNOWN，任务保存人工调查原因和时间并告警，不宣称失败或成功。PR15 的外部枚举不在本 PR 实现。

## 调度与展示

L1 以持久 available_at 实现 5/15/30/60/120 秒延迟并带有可测试抖动；快速领取循环默认不超过 5 秒，租约默认 30 秒。L2 每 5 分钟扫描 UNKNOWN、失效租约、非终态和成功但缺财务证据的支付，幂等唤醒原任务，不能创建第二份执行资格或重置活跃租约。长时间未解决须产生持久老化告警，不以无限重试冒充收敛。账户限流在 PostgreSQL 中跨 Worker 生效，延迟不能让其他账户永久饥饿。

成功创建收银台仍需后续查询，创建操作 RESOLVED 不等于整个支付完成；成功 capture 的 financialStatus 保持 PENDING_EVIDENCE，PR10 前不记账。取消恢复只按原 Session 的权威状态处理，不重复创建 Session，不把成功捕获改成 CANCELED。

GET payment 增加 recovery 摘要：status（IDLE/WAITING/RUNNING/ESCALATED）、nextAttemptAt、attemptCount、lastQueryAt、reason。保留现有四维支付状态和 lastProviderConfirmedAt，不暴露租约 token、密钥或命令。多个操作按需要人工调查/运行/等待的优先级汇总。无新公共写 API。

## 验证与边界

真实 PostgreSQL/runtime role + 独立持久 Fake，注入可控 Clock。覆盖提交后调用前、远端受理后丢响应、本地回写前/查询后崩溃，两个 Worker 竞争，旧 Worker 越租约返回与锚点再发现，暂时查空后成功，取消/成功竞争，幂等窗口过期与独立远端效果计数；验证无事务内网络调用、调度/限流/权限/API 恢复摘要、V4 升级及既有回归。

L1/L2 及任务领取时延是确定性本地实验，不宣称生产 SLA；PR08 Inbox、PR09 通知、PR10 BT 导入、PR11 退款接入、PR15 L3/人工操作 API 仍留在后续。PR07 故障实验不需要在真实 Stripe 重复扣款。基线沿用刚完成且源码摘要匹配的 233 项通过记录。
