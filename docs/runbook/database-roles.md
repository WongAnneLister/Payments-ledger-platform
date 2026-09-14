# 运行说明：数据库角色与权限分离

版本：PR01 · 状态：角色与默认权限已落地；账本表的 INSERT-only 授予随 PR03 建表迁移落地

关联：[ADR-0004 不可变账本](../adr/0004-immutable-ledger.md) · [账本科目 §6](../ledger/accounts.md) · [七项不变量](../ledger/invariants.md) · [本地开发](local-development.md)

## 1. 为什么要分离角色

账本的不可变性如果只靠 Java 代码保证，任何绕过服务层的直连 SQL 都能推翻它。
验收 §3.1 明确要求：**用实际应用运行角色测试**，不能用 owner / superuser 得到「拒绝」后就宣称数据库已保护。

因此权限分离不是运维洁癖，而是 AC-V1-17 的**验证前提**。

## 2. 三个角色

| 角色 | 用途 | DDL | 账本表权限 | 谁使用 |
|---|---|---|---|---|
| 容器超级用户（本地 `postgres`） | 仅本地容器初始化 | ✅ | — | Compose 初始化脚本 |
| `payments_migrator` | 执行 Flyway 迁移 | ✅ | 建表者 | `spring.flyway.*` |
| `payments_app` | 应用运行时连接 | ❌ | **只有 SELECT + INSERT** | `spring.datasource.*` |

配置上的体现（`src/main/resources/application.yml`）：

```yaml
spring:
  datasource:            # 运行时：payments_app
    username: ${DB_APP_USER:payments_app}
  flyway:                # 迁移：payments_migrator
    user: ${DB_MIGRATION_USER:payments_migrator}
```

**这是两条独立的连接配置，不是同一个账号。**

## 3. 当前已落地的授予

`scripts/db/init-roles.sql` 在容器首次初始化时执行：

```sql
GRANT CREATE, CONNECT ON DATABASE <db> TO payments_migrator;
GRANT CONNECT           ON DATABASE <db> TO payments_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA IF NOT EXISTS platform AUTHORIZATION payments_migrator;
GRANT USAGE ON SCHEMA platform TO payments_app;

-- 迁移角色今后新建的表，默认只给应用角色 SELECT + INSERT
ALTER DEFAULT PRIVILEGES FOR ROLE payments_migrator IN SCHEMA platform
    GRANT SELECT, INSERT ON TABLES TO payments_app;
```

关键点：**默认权限里没有 UPDATE、没有 DELETE。** 这是「默认拒绝」而非「默认放开再收紧」。

PR01 实测确认（`payments_app` 在 `platform` 上的默认 ACL）：

```text
r | payments_app=ar/payments_migrator     -- a=INSERT, r=SELECT
S | payments_app=rU/payments_migrator     -- 序列：SELECT + USAGE
```

没有 `w`(UPDATE)、没有 `d`(DELETE)。

### 3.1 为什么所有对象都放在 `platform` 而不是 `public`

PostgreSQL **15 起** `public` schema 不再默认向 `PUBLIC` 授予 `CREATE`，且其属主是 `pg_database_owner`
——`payments_migrator` 并非属主，因此在 `public` 下建表会被拒绝。

PR01 手动对 Compose 环境启动时实际踩到了这个问题：Flyway 默认把 `flyway_schema_history`
建在 `public`，启动直接失败（`permission denied for schema public`）。

修正是把 Flyway 也指向 `platform`（属主即迁移角色），而**不是**放宽 `public` 的权限：

```yaml
spring:
  flyway:
    schemas: platform
    default-schema: platform
    create-schemas: true
```

> Testcontainers 的集成测试**没有**暴露这个问题，因为它们不执行 `init-roles.sql`、
> 且使用容器超级用户连接。`FlywayBaselineIT` 已补上回归断言，要求历史表必须落在 `platform`。
> 这也说明：**容器内的超级用户测试不能替代受限角色下的验证**——这正是 AC-V1-17
> 要求「用实际应用运行角色测试」的原因。

## 4. 后续 PR 的授予纪律

| 表类别 | 权限 | 归属 PR |
|---|---|---|
| `journal` / `journal_entry` | **INSERT only**，永不授予 UPDATE / DELETE | PR03 |
| `balance_projection` | 仅经 `LedgerPostingService` 的过账事务写入 | PR03 |
| `provider_operation` | 需要 UPDATE（租约、状态回写）→ **逐表**显式 GRANT | PR06 / PR07 |
| `refund_control` | 需要 UPDATE（C/R/H）→ 逐表显式 GRANT | PR11 |
| 其它控制行 | 按需逐表授予 | 各自 PR |

**禁止**为了省事执行 `GRANT ALL ON ALL TABLES IN SCHEMA platform TO payments_app`。
那一条命令会让 AC-V1-17 的全部封存防护测试失去意义。

## 5. PR03 必须用应用角色验证的绕过路径

以下每条都必须以 `payments_app` 连接执行，并**失败**（验收 §3.1）：

1. 插入空 Journal、单条 Entry、不平衡组合 → 提交时失败
2. 一张凭证内混用组织 / 环境 / 币种 → 失败且无投影与 Outbox 残留
3. 封存后 `UPDATE` / `DELETE` Journal 或 Entry → 失败
4. 封存后插入一条 Entry，**或一对借贷相等的 Entry** → 均失败
5. 同事务内构造完整凭证并封存 → **成功**（受控写入是允许的）
6. 相同规范事件重复请求 → 返回原 Journal；改金额 / source / fee 不得被当成普通 duplicate
7. 多入口同时接受同一 BT → PSP 投影只变化一次
8. 隔离 fixture 中故意改错投影 → 独立重建必须发现残差

## 6. 生产部署差异

`scripts/db/init-roles.sql` **只用于本地容器**。生产或共享环境：

- 角色与口令由基础设施流程管理，**不写入版本库**。
- 口令通过环境变量或密钥管理服务注入，不进日志、不进镜像。
- 迁移在部署流水线中以 `payments_migrator` 单独执行，应用进程**不持有** DDL 权限。

## 7. 备份恢复演练的约束

演练只在**新项目明确隔离的测试库**执行（AC-V1-37）：

- 演练前核对目标库名、host、port 与隔离依据，并记录。
- **绝不**删除、迁移或覆盖其他项目的数据库。
- 恢复后先限制资金写入，独立重建账本并重扫外部证据，再按资格恢复写流程。

本项目的集成测试使用 Testcontainers 一次性容器与唯一命名的新库
（如 `pr01_isolated_migration_check`），天然满足上述隔离要求。
