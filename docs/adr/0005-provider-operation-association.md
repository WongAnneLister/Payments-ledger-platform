# ADR-0005：`provider_operation` 关联方式

状态：Accepted（PR00 冻结） · 日期：2026-09-10

> **这是 PR00 必须做出的两个强制决策之一。**

## 背景

支付创建与退款创建都是"资金写操作"，都需要：持久化的执行意图、操作级幂等键、请求指纹、租约、崩溃恢复、UNKNOWN 处理、查询取证、升级告警。

如果两者各自实现一套，就会出现 PRD 明确警告的局面：**支付能恢复，退款永久占额**。路线图 §4.2 与 AC-V1-10 要求"支付和退款共享 `provider_operation` / 恢复机制"。

问题是：一张 `provider_operation` 表如何同时安全地指向 `payment` 或 `refund`？而且退款表在 PR11 才存在，`provider_operation` 在 PR06 就要建。

## 决策

**采用 `payment_id` / `refund_id` 互斥外键。PR06 建表时 `payment_id` 带 FK、`refund_id` 为预留列；PR11 追加迁移为 `refund_id` 加 FK。**

```sql
-- PR06
CREATE TABLE provider_operation (
  id                uuid PRIMARY KEY,
  organization_id   uuid NOT NULL,
  environment_id    uuid NOT NULL,
  provider_account_id uuid NOT NULL,
  livemode          boolean NOT NULL,
  operation_target  text NOT NULL CHECK (operation_target IN ('PAYMENT','REFUND')),
  operation_type    text NOT NULL,          -- CHECKOUT_CREATE / CHECKOUT_EXPIRE / REFUND_CREATE ...
  operation_key     text NOT NULL,          -- 持久化的操作级幂等键
  request_fingerprint text NOT NULL,
  payment_id        uuid NULL REFERENCES payment(id),
  refund_id         uuid NULL,              -- 预留列，PR11 加 FK
  status            text NOT NULL,
  resolution        text NOT NULL,
  lease_owner       text, lease_until timestamptz, lease_token uuid,
  attempt_count     int NOT NULL DEFAULT 0,
  external_object_id text,
  version           bigint NOT NULL DEFAULT 0,
  ...
  CONSTRAINT ck_operation_target_exclusive
    CHECK (num_nonnulls(payment_id, refund_id) = 1),
  CONSTRAINT ck_operation_target_matches_type CHECK (
    (operation_target = 'PAYMENT' AND payment_id IS NOT NULL) OR
    (operation_target = 'REFUND'  AND refund_id  IS NOT NULL)
  ),
  CONSTRAINT uq_operation_key
    UNIQUE (organization_id, provider_account_id, livemode, operation_type, operation_key)
);

-- PR11 追加迁移（不原地重写已发布迁移）
ALTER TABLE provider_operation
  ADD CONSTRAINT fk_operation_refund FOREIGN KEY (refund_id) REFERENCES refund(id);
```

配套：

- **只有一张 `recovery_task`**（PR07 创建），`resource_type ∈ {PAYMENT, REFUND, FINANCIAL}`；PR11 注册退款类型，PR15 扩展 L3。**不得**新建第二张恢复表。
- `operation_key` 按操作类型隔离：create / expire / refund **不共用**同一键。

## 被否方案

| 方案 | 为什么否 |
|---|---|
| **无约束的 `resource_type` + `resource_id` 字符串对** | 数据库**无法**校验 `resource_id` 是否真的存在。孤儿操作、拼写错误、跨类型错指全部不可见。PR00 提示词明确禁止"无人校验的任意字符串 `resource_id`"。 |
| **有数据库约束的关联表**（`operation_payment_link` / `operation_refund_link`） | 技术上可行，也是提示词允许的备选。否决理由：① 每次恢复都要多一次 JOIN，而恢复路径是最需要简单可靠的路径；② "一个操作恰好关联一个资源"需要额外的唯一约束 + 跨表 CHECK 才能保证，比 `num_nonnulls(...) = 1` 复杂得多；③ 关联表自身成为需要维护一致性的第三方。 |
| **两张表：`payment_operation` + `refund_operation`** | 直接违反单一恢复框架要求。通用 Worker 需要 UNION 两张表，租约与扫描逻辑翻倍，必然漂移。 |
| **PR06 就建 `refund` 表以便立刻加 FK** | 提前实现未来模块，违反 PR 范围纪律；且退款表结构要等 PR11 的额度模型确定。 |
| **表继承 / 多态 ORM 映射** | PostgreSQL 表继承不传播外键与唯一约束；ORM 多态在原生 SQL 恢复查询中反而增加复杂度。 |

## 后果

**正面**

- `num_nonnulls(payment_id, refund_id) = 1` 一行 CHECK 就保证了"恰好关联一个资源"。
- 恢复 Worker 的查询无需 JOIN 关联表，`WHERE lease_until < now() AND status IN (...)` 直接扫描。
- 两个 FK 都是真实外键，孤儿操作在数据库层不可能存在。
- 新增第三种目标（如未来的 payout 操作）只需加一列 + 更新两条 CHECK，迁移可控。

**负面与缓解**

| 负面 | 缓解 |
|---|---|
| 每增加一种目标类型就要改表结构 | 本期只有两种；PRD 范围明确不扩张 |
| PR06 到 PR11 之间 `refund_id` 无 FK 保护 | 该期间 `operation_target` 只能是 `PAYMENT`，CHECK 已保证 `refund_id IS NULL`；PR11 追加 FK 时无历史数据需要回填 |
| 稀疏列（两列必有一个为 NULL） | 部分索引 `WHERE payment_id IS NOT NULL` 控制索引体积 |

## 验证归属

| 验证 | PR | AC |
|---|---|---|
| 两列同时为 NULL 或同时非 NULL 被拒绝 | PR06 | AC-V1-04 |
| `operation_target` 与实际填充列不一致被拒绝 | PR06 | AC-V1-04 |
| 崩溃后可领取已提交的支付执行义务 | PR07 | AC-V1-10 |
| **退款复用同一张 `provider_operation` 与同一张 `recovery_task`** | PR11 | AC-V1-09、10、20 |
| PR11 追加 FK 迁移在隔离空库与升级路径均成功 | PR11 | 工程门槛 |
| 不存在第二套退款恢复表 | PR11 | AC-V1-10 |
| L3 扩展复用同一恢复框架 | PR15 | AC-V1-28 |
