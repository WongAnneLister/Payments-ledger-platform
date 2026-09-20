# PR02 身份与本地 fixture

新增内容：应用 Bearer 与 OpsBearer 独立认证、不可变 TenantContext、复合范围外键、受控价格与政策读取。支付/账本/Stripe/运营动作仍属后续 PR。所有演示 Provider 都是 FAKE；JPY 与第二组织仅存在于隔离测试。

## 本地启动

先按 [README](../../README.md) 配置 PostgreSQL 和 Java 21。Spring 进程不会自动加载 Compose 的 `.env`；在同一终端导出所需数据库变量。以下口令仅对应仓库默认本地 Compose，可按本地配置修改。

```bash
export JAVA_HOME="$HOME/.jdks/amazon-corretto-21.jdk/Contents/Home"
export DB_APP_PASSWORD=payments_app_pw
export DB_MIGRATION_PASSWORD=payments_migrator_pw

# 生成两个独立的 256-bit secret，不打印；保留在当前终端供后续 curl 使用。
export LOCAL_APP_SECRET="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"
export LOCAL_OPS_SECRET="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"
export LOCAL_FIXTURES_ENABLED=true
./mvnw spring-boot:run -Dspring-boot.run.profiles=api,local
```

首次开启只允许空身份库；复用匹配的 fixture 数据库时校验相同凭据，不覆盖或轮换摘要。已有其他组织、secret 不同或未启用 `local` profile 时启动失败。需要跨终端使用时，把生成的 secret 保存在本地安全存储或被忽略的 `.env`，不要提交。凭据有效期为初始化起 365 天；失效/吊销后的更新由受控配置维护完成，重启不会自动续期或恢复权限。日常启动可以关闭 `LOCAL_FIXTURES_ENABLED`，已存数据库身份仍然可用。

初始化使用 Flyway 的迁移连接和单个事务；运行连接对配置表只有 SELECT。fixture 仅提供一个 USD、一个应用、一个 FAKE Provider、一个 OPS_VIEWER、10000 minor units 的价格及 1500 bps 单合作方政策。没有任何支付或资金写入。

```bash
# 在已设置上述变量的终端中运行；API 服务可另起终端运行。
curl -s http://localhost:8080/v1/identity \
  -H "Authorization: Bearer app_10000000-0000-0000-0000-000000000041.$LOCAL_APP_SECRET"
curl -s http://localhost:8080/v1/prices/10000000-0000-0000-0000-000000000081 \
  -H "Authorization: Bearer app_10000000-0000-0000-0000-000000000041.$LOCAL_APP_SECRET"
curl -s http://localhost:8080/ops/identity \
  -H "Authorization: OpsBearer ops_10000000-0000-0000-0000-000000000061.$LOCAL_OPS_SECRET"
```

## 认证与隔离

- secret 仅存 bcrypt cost 12 摘要；Token 内 UUID 只负责定位凭据，不能单独认证。日志不输出凭据。
- 认证读取凭据与组织/环境/应用/Provider 状态，检查 `not_before <= now < expires_at`。币种与 scope 来自数据库。认证使用只读可重复读事务，不把失效信息长期缓存。
- `organization_id` 等归属字段支持 snake_case、camelCase、`X-Organization-Id` 等 Header 拼写；任何不一致的 Header、查询参数或 JSON 归属断言返回 403。匹配只代表断言通过，不参与身份构造。
- JSON 请求上限 64 KiB，拒绝重复字段与多个 JSON 文档，校验后保留原始字节供后续 Controller 使用。Webhook 入口不经过上述 Bearer/JSON 流程；PR08 前不会应答成功。
- 应用只可读取自身 application；跨组织/环境/同组织其他应用返回 404。受控价格在组织/环境内共享，但受 `payments:read` 与凭据币种限制。
- 缺失/错误/失效凭据为 401；身份存储不可用为可重试 503；Worker 不暴露以上身份接口。
- API/Worker 在上下文初始化阶段检查全部数据库环境及 Provider，不允许 live 配置。`platform.livemode=true` 或已识别的 `sk_live_` / `rk_live_` 配置也拒绝。PR05 接入真实 Stripe 时仍需扩展其正式配置绑定。

## 数据库权限和配置维护

V2 为 `payments_app` 明确撤销身份配置表写权限、仅保留 SELECT。若自定义 `DB_APP_USER`，须由数据库管理员对该自定义角色配置等价授权；不要把迁移角色用作运行角色。

受控配置变更需在迁移角色事务中执行，并更新 `updated_at` 和 `version`。不提供公共组织/凭据管理接口。凭据的组织、环境、应用和 Provider 绑定由复合外键保护；Provider 外部账户引用全局唯一。价格必须为正 bigint，USD 指数固定 2，JPY 固定 0。当前政策 fixture 每版本只有一个合作方，总基点不超过 10000。

## 验证

```bash
./mvnw -B verify
python3 scripts/ci/check_openapi.py
python3 scripts/ci/check_doc_links.py
git diff --check
```

集成测试使用一次性 PostgreSQL 数据库，认证 HTTP 实际以只读 `payments_app` 连接；种子由测试的管理连接写入。不要对日常开发库执行测试中的清理语句。完整证据与验收边界见 [PR02 验证记录](../evidence/pr02.md)。
