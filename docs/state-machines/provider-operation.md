# 状态机：ProviderOperation（支付与退款共用）

版本：PR00 · 状态：冻结设计，未实施

关联：[PRD §14.3](../product/payment-platform-prd.md) · [Provider 端口](../contracts/provider.md) · [AccountScope §8](../contracts/account-scope.md) · [ADR-0003 未知结果](../adr/0003-provider-unknown-result.md) · [ADR-0005 操作关联](../adr/0005-provider-operation-association.md)

**这是全项目唯一的资金写操作执行模型。** 支付与退款共用同一张 `provider_operation`、同一张 `recovery_task`、同一套租约与恢复协调器。禁止为退款另建一套无法被通用 Worker 观测的操作体系（路线图 §4.2、AC-V1-10）。

## 1. 关联模型（PR00 冻结决策）

采用 **`payment_id` / `refund_id` 互斥外键**：

```sql
operation_target  text NOT NULL CHECK (operation_target IN ('PAYMENT','REFUND'))
payment_id        uuid NULL REFERENCES payment(id)      -- PR06 建立
refund_id         uuid NULL                              -- PR06 预留列，PR11 加 FK

CHECK (num_nonnulls(payment_id, refund_id) = 1)
CHECK ((operation_target='PAYMENT' AND payment_id IS NOT NULL)
    OR (operation_target='REFUND'  AND refund_id  IS NOT NULL))
```

理由与被否方案见 [ADR-0005](../adr/0005-provider-operation-association.md)。**禁止**用无约束的 `resource_type` + `resource_id` 字符串对替代。

## 2. 状态定义

| 状态 | 含义 | 远端是否可能已有效果 |
|---|---|---|
| `PREPARED` | 执行意图已随业务事务提交，尚未发送 | 否 |
| `SENDING` | 已开始发送（进程可能在此崩溃） | **可能** |
| `ACKNOWLEDGED` | 收到明确响应（成功或明确拒绝） | 是/否，均已知 |
| `UNKNOWN` | 超时、暂态错误或查询暂空，结果未确定 | **可能** |
| `RESOLVED` | 经查询/事件/BT 取证后事实已确定 | 已知 |
| `ESCALATED` | 无法安全自动恢复，需人工调查 | **可能** |

**`SENDING` 在崩溃后不证明请求未发出**——这是本状态机存在的根本原因（PRD §13、F-05/F-06）。

## 3. 关键字段

| 字段 | 用途 |
|---|---|
| `operation_key` | 持久化的操作级幂等键。create / confirm / refund **不共用**同一键 |
| `request_fingerprint` | 请求语义指纹，用于检测"同键不同内容" |
| `lease_owner` / `lease_until` / `lease_token` | 租约排他；仅保护**本地领取**，不能撤销已发出的远端请求 |
| `attempt_count` / `last_query_at` | 退避与老化告警 |
| `resolution` | `KNOWN` / `UNKNOWN` / `CONFLICT` |
| `last_provider_status` | provider 原值，**不被** `UNKNOWN` 覆盖 |
| `external_object_id` | 拿到即成为最有力的取证锚点 |
| `provider_evidence` | 每次调用的 `ProviderEvidence`，**超时与失败时同样必须持久化** |

## 4. 允许的转换

| # | From | To | 触发 | 守卫 |
|---|---|---|---|---|
| O1 | — | `PREPARED` | 业务事务提交 | 与订单/退款同事务；网络调用在事务外 |
| O2 | `PREPARED` | `SENDING` | Worker 取得租约并开始调用 | 短事务 claim + 发放 `lease_token` |
| O3 | `SENDING` | `ACKNOWLEDGED` | `SUCCESS` / `DEFINITIVE_REJECT` / `AUTH_REQUIRED` | 必须持 **当前** `lease_token` 回写 |
| O4 | `SENDING` | `UNKNOWN` | `TIMEOUT_UNKNOWN` / `RETRYABLE_TRANSIENT` | 保存 evidence |
| O5 | `UNKNOWN` | `RESOLVED` | 查询/事件/BT 取回确定事实 | 证据级别 ≥ 权威查询 |
| O6 | `ACKNOWLEDGED` | `RESOLVED` | 事实并入业务状态 | — |
| O7 | `UNKNOWN` | `SENDING` | **窗口内**用原键安全重试 | 仍在已验证保留窗口 **且** 持有效租约 |
| O8 | `UNKNOWN` | `ESCALATED` | 窗口已过、无外部 ID、无法排除已受理 | 创建人工调查任务 + 告警 |
| O9 | `PREPARED` | `SENDING` | 崩溃后由新 Worker 重领 | 原 `operation_key` 与原指纹 |
| O10 | `ESCALATED` | `RESOLVED` | 人工核实并取得权威证据 | 需审计记录 |

## 5. 被拒绝的转换

| # | 被拒绝的行为 | 原因 |
|---|---|---|
| X1 | `UNKNOWN` → 换一个新 `operation_key` 重发 | 会造成第二次真实扣款（F-05） |
| X2 | `UNKNOWN` → 直接判定为业务失败 | UNKNOWN ≠ 失败（PRD 全文核心约束） |
| X3 | 窗口已过 + 无外部 ID → 用原键盲目重发 | 提供方可能按新请求处理 → 重复扣款（AC-V1-09） |
| X4 | 持**过期** `lease_token` 回写控制状态 | 旧 Worker 覆盖新结果（AC-V1-10） |
| X5 | 因"等待够久"就认定可安全重试 | 时间不是证据（验收 §4.1） |
| X6 | 在数据库事务内执行网络调用 | 长事务 + 锁持有（PRD §14.3） |
| X7 | 丢弃过期 Worker 返回的成功对象 ID | 可能是唯一的取证锚点 |
| X8 | 为退款新建独立的 `refund_operation` / 第二张 `recovery_task` | 违反单一恢复框架（路线图 §4.2） |

**X4 的正确处理**：过期 Worker 的返回**不更新**控制状态，但**必须**作为 `provider_observation` 另存为证据，供后续核对（AC-V1-10）。丢弃它等于丢弃可能唯一的外部 ID。

## 6. 幂等重复

| 场景 | 期望行为 |
|---|---|
| 同 `operation_key` 在窗口内重发 | 提供方返回缓存结果；本地条件更新命中同值，不新增操作 |
| 两个 Worker 同时重领 | 只有一个 claim 成功（条件更新 + `lease_token`），另一个无副作用退出 |
| 重复调用查询 | 幂等；只更新 `last_query_at` 与 evidence |
| 重复 `RESOLVED` 归并 | 条件更新命中同值，不重复触发业务效果与通知 |
| 死锁/序列化失败重试 | 重跑**完整本地事务**并保持幂等；**不**重复发起外部资金请求 |

## 7. UNKNOWN 决策树

```text
                 ┌──────────────┐
                 │   UNKNOWN    │
                 └──────┬───────┘
                        ▼
             有 external_object_id ?
              ├── 是 ──► 按 ID 权威查询 ──► O5 RESOLVED
              └── 否
                        ▼
        有持久业务关联（order_key / 可信 metadata）?
              ├── 是 ──► 外部枚举取证（L3 通道） ──► O5 / 人工
              └── 否
                        ▼
             仍在已验证幂等保留窗口内 ?
              ├── 是 ──► O7 用【原键】安全重试
              └── 否
                        ▼
              能否排除"已被受理" ?
              ├── 能 ──► 按规则重新发送
              └── 否 ──► O8 ESCALATED
                          ✗ 不换键  ✗ 不重发  ✗ 不标记失败
                          ✓ 保留 UNKNOWN  ✓ 告警  ✓ 人工调查
```

## 8. L1 / L2 / L3 的分工

| 层 | 触发 | 默认节奏 | 覆盖范围 | 覆盖不了什么 |
|---|---|---|---|---|
| L1 延迟查询 | 刚提交的操作 | 5/15/30/60/120 秒，带抖动 + 账户限流 | 已知操作与外部 ID | 本地整条记录丢失 |
| L2 本地扫描 | 定时 | 每 5 分钟 | `UNKNOWN`、过期租约、缺 BT、长时间处理中 | 无本地锚点的外部成功支付 |
| L3 外部枚举 | 次业务日 | T+1 窗口 + 重叠回看 | Stripe 侧 BT 与来源对象 | 提供方尚未提供证据 |

**L2 的 5 分钟扫描是兜底发现，不是唯一的重领机制**；租约到期领取循环默认 ≤ 5 秒（PRD §17.2）。

崩溃后 120 秒内重新领取任务是**待验证目标**，且仅在"本地依赖已恢复、无账户限流、队列处于基线负载"条件下成立。它**不是**对外部争议结果或支付最终完成的承诺。

## 9. 租约语义

```text
claim（短事务）:
  UPDATE provider_operation
     SET lease_owner = :worker, lease_until = now() + interval '30 seconds',
         lease_token = gen_random_uuid(), attempt_count = attempt_count + 1
   WHERE id = :id
     AND (lease_until IS NULL OR lease_until < now())
     AND status IN ('PREPARED','UNKNOWN')
  -- 受影响行数 = 0 → 本 Worker 放弃，无副作用

调用（事务外，不持有任何业务锁）

回写（条件更新）:
  UPDATE provider_operation
     SET status = :new, resolution = :res, last_provider_status = :ps, ...
   WHERE id = :id AND lease_token = :token
  -- 受影响行数 = 0 → 租约已被抢占；结果另存为 provider_observation
```

租约**只保护本地领取**。它不能撤销、不能中止、也不能证明远端请求没有发出。
