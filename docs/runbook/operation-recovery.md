# PR07 操作恢复与支付 L1/L2

恢复复用 PR06 的 `provider_operation`、`recovery_task` 和 `provider_observation`。目标是在进程中断或响应丢失后继续查明原操作，保留正确的付款事实。支付状态、结果确定性、财务状态和恢复进度分别展示。

## 运行方式

API 和 Worker 连接同一 PostgreSQL。API 仍提供创建、查询、取消支付；Worker 执行持久任务，快速扫描可领取任务并周期执行 L2 补扫。可以同时运行多个 Worker，执行资格由数据库中的租约、token 和版本控制，不依赖单 JVM 锁。

Worker 使用 `worker` profile；可通过 `platform.payments.worker.enabled=false` 关闭调度。下列恢复配置项使用 `platform.recovery` 前缀：

| 属性 | 默认值 | 作用 |
| --- | --- | --- |
| `lease` | `PT30S` | 操作领取租约 |
| `poll-ms` | `5000` | 快速领取循环间隔 |
| `l2-poll-ms` | `300000` | L2 补扫间隔 |
| `l1-delays` | `PT5S,PT15S,PT30S,PT60S,PT120S` | L1 延迟序列，耗尽后使用末项 |
| `jitter-ratio` | `0.1` | L1 延迟抖动幅度 |
| `unresolved-age` | `PT1H` | 未决结果老化告警阈值 |
| `fake-verified-retention` | `PT0S` | Fake 未知创建可安全原键重试的已验证窗口；0 表示不允许 |
| `account-batch-interval` | `PT1S` | 每账户恢复查询批次间隔，在 PostgreSQL 中协调所有 Worker |

L1 的默认调度间隔为 5/15/30/60/120 秒并带抖动，默认后续保持 120 秒；状态与下次时间保存在数据库，重启不会丢掉已提交的义务。一个账户的恢复批次最多允许 32 次 Provider 调用，Stripe 在底层按实际 HTTP 请求扣减共享预算（包括账户核对、每页查询和取消命令）；这是**恢复批次限流**，不是面向所有客户端的 API QPS 限制。取消的前查、过期操作和后查共用同一预算。用尽查询预算时保留 `RETRYABLE_TRANSIENT` / 不完整证据，不能把部分结果当作已确定。

未知结果从 `payment.unresolved_since` 开始计算老化，恢复 KNOWN 后清除该计时；合法待客户付款的时间不算作未知结果持续时间。首次发送时间和安全重试窗口独立保存且不可延长。

未配置 Provider 的部署仍能启动；错误账户绑定、停用配置或币种不允许执行远端命令，不能让它们堵住其他可执行账户。配置及 fixture 隔离要求沿用[业务支付手册](business-payments.md)。

## 自动恢复如何选择动作

| 当前证据 | 自动动作 |
| --- | --- |
| PREPARED，尚未发送 | 使用已保存的原命令和原操作键执行 |
| 已有 Session ID | 权威查询同一 Session、PI 和全部可获取 Charge，不新建 Session |
| 当前记录缺 ID，但过期 Worker 的观察保存了锚点 | 查询并核对同范围、同操作、同支付的锚点，通过后关联原支付 |
| UNKNOWN/过期 SENDING，无外部 ID，仍在已验证幂等窗口内 | 原键、原指纹、原参数重试；绝对 Checkout 到期时间也保持不变 |
| 无 ID，保留窗口未验证或已过期，无法排除远端已受理 | 持久升级为 ESCALATED，保留 UNKNOWN/CONFLICT，记录原因并告警 |
| 查询暂空、超时或限流 | 保存观察、等待后续查询，不当作支付失败或未执行的证明 |
| Session 自然到期，权威证据完整且可证明未付款 | 收敛 CANCELED；仅本地时间到期不够 |
| 取消与成功 capture 竞争 | 保留成功资金事实；不能把成功支付改成未付款取消 |

**真实 Stripe 当前不自动重试无 ID 的未知创建。** 本项目尚未完成真实幂等保留窗口验证；本次 Fake 窗口实验不能作为 Stripe 窗口证据。Fake 的已验证重试配置必须不大于其 `platform.provider.fake.retention`。重试不会重置首次发送时间，也不会延长安全窗口。

过期租约只能使新 Worker 获得本地执行资格，不能撤销旧 Worker 已发出的远端调用。旧结果仍写入不可变观察；旧 token 不能覆盖新控制状态。数据库事务中没有 Provider 网络等待。

## API 中的恢复进度

`GET /v1/payments/{paymentId}` 在原有四维状态之外返回 `recovery`：

```json
{
  "status": "WAITING",
  "nextAttemptAt": "2026-09-25T12:00:15Z",
  "attemptCount": 1,
  "lastQueryAt": "2026-09-25T12:00:00Z"
}
```

以上为字段示例，不是某次实测记录。`IDLE` 表示当前没有待执行恢复，`WAITING` 表示持久等待，`RUNNING` 表示有效租约持有者正在处理，`ESCALATED` 表示需要调查。无值时间可省略；升级时 `reason` 给出受控原因，不返回密钥、租约 token 或完整 Provider 命令。

创建 Checkout 的操作可以已经 RESOLVED，但客户还没付款，因此支付仍需要后续查询。成功 capture 也可以处于 `financialStatus=PENDING_EVIDENCE`；PR10 导入财务事实并过账之前，不产生账本凭证。

## 调查与证据

通过受控数据库连接读取操作、任务和历史观察，按组织、环境和 Provider 账户限制范围。先核对原请求键、指纹、首次发送时间、租约、外部 ID 和 Provider 请求证据；**不要直接把操作改回 PREPARED，也不要换键重发**。

ESCALATED 是持久的人工调查义务。本 PR 不提供解除升级的公共写 API；PR15/16 再接入外部枚举、审计核实与运营处理。普通 GET 不应越过人工调查边界假装已经恢复。

本 PR 的故障验证使用真实 PostgreSQL 及独立持久 Fake。无重复效果由 Fake 的会话创建、capture 和效果日志分别证明，不以本地只有一行 payment 代替外部证据。可控时钟用于租约到期、调度、保留窗口和老化测试；不通过等待真实时间制造通过。

## 边界

- 自动恢复当前接入支付，退款仍由 PR11 使用同一框架接入。
- Webhook Inbox/异步规范观察属于 PR08；商户通知属于 PR09。
- 财务流水导入和记账属于 PR10，外部枚举回补属于 PR15。
- 确定性本地实验不代表生产负载下的恢复时延 SLA；完整 R0 真实能力门槛仍为 BLOCKED。
