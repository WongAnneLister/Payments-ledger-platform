# Payments Ledger Platform

Stripe 支付网关与复式账本。模块化单体，API / Worker 分角色运行，共享同一个 PostgreSQL 事务域。

**当前状态：PR14 出款与模拟银行核对已接入。** 已实现可信身份、不可变账本、Fake/Stripe 适配器、支付与退款 API、共享操作恢复、Webhook 和 Outbox / Kafka 商户通知。BT 导入支持实际金额/手续费入账、去重、隔离与依赖等待；内部分润按下单快照和累计退款额度计提，退款预占、释放和冲突处理会原子调整分润凭证，并支持历史补计提。[分润运行说明](docs/runbook/revenue-share.md)介绍规则和启用方式。争议支持 Webhook/查询/BT 恢复、应收与损失分账、退款阻断和分润原子撤回/恢复，详见[争议运行说明](docs/runbook/disputes.md)。Payout 支持查询/Webhook/BT 摄入、在途记账、模拟银行导入与本金分配，并保留失败返还交叠冲突，详见[出款与银行运行说明](docs/runbook/payout-bank.md)。独立完整性对账由 PR15 接入。PR05 已实测付款、退款及真实回调验签；各期实际验证来源以 evidence 记录为准。
运行说明见 [身份配置](docs/runbook/identity-and-fixtures.md)、[账本核心](docs/runbook/ledger-core.md)、[Provider 模拟器](docs/runbook/provider-simulator.md)和[Stripe 契约验证](docs/runbook/stripe-contract-check.md)。本地 fixture 通过不代表真实 Stripe 契约通过。
设计基线见 [`docs/`](docs/)；本 README 只讲怎么跑起来。

> 本项目完全从零实现，只使用 Stripe 测试环境。`livemode=true` 的配置会在**启动时**被拒绝。

## 快速开始

### 先决条件

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **21** | 基线要求；`java -version` 必须是 21.x |
| Docker | 支持 Compose v2 | 本地 PostgreSQL / Redis / Kafka |
| Maven | 不需要预装 | 用仓库内的 Maven Wrapper |

### 1. 环境变量

```bash
cp .env.example .env
```

`.env` 已被 `.gitignore` 排除，**不要提交**。

### 2. 启动基础设施

```bash
docker compose up -d
docker compose ps          # 等到 postgres / redis / kafka 全部 healthy
```

### 3. 构建

**Unix / macOS**

```bash
./mvnw clean verify
```

**Windows（PowerShell 或 cmd）**

```bat
mvnw.cmd clean verify
```

> `verify` 会跑集成测试，需要 Docker 在运行。只想快速编译加单元测试用 `./mvnw test`。

### 4. 分别启动两个运行角色

运行角色**必须显式指定**，否则进程会快速失败——这是刻意设计，避免出现既是 API 又是 Worker 的混合进程。

**Unix / macOS**

```bash
# API 角色（默认 8080）
./mvnw spring-boot:run -Dspring-boot.run.profiles=api

# Worker 角色（默认 8081），另开一个终端
./mvnw spring-boot:run -Dspring-boot.run.profiles=worker
```

**Windows**

```bat
mvnw.cmd spring-boot:run -D"spring-boot.run.profiles=api"
mvnw.cmd spring-boot:run -D"spring-boot.run.profiles=worker"
```

**用打包产物启动**

```bash
./mvnw clean package -DskipTests
java -jar target/payments-ledger-platform-0.1.0-SNAPSHOT.jar --spring.profiles.active=api
java -jar target/payments-ledger-platform-0.1.0-SNAPSHOT.jar --spring.profiles.active=worker
```

### 5. 验证

```bash
curl -s localhost:8080/actuator/health    # API 角色
curl -s localhost:8081/actuator/health    # Worker 角色
# 期望：{"status":"UP"}
```

健康检查公开；API 角色提供以下业务入口：

| 接口 | 权限 |
|---|---|
| `GET /v1/identity` | 有效业务应用 Bearer |
| `GET /v1/applications/{id}` | 只能读取当前凭据绑定的应用 |
| `GET /v1/prices/{id}` | `payments:read` + 同组织/环境 + 已授权币种 |
| `GET /ops/identity` | 独立 OpsBearer |
| `POST /v1/payments` | `payments:write` + Idempotency-Key + 受控价格 |
| `GET /v1/payments/{paymentId}` | `payments:read` + 原应用/账户范围 |
| `POST /v1/payments/{paymentId}/cancel` | `payments:write` + 独立 Idempotency-Key |

业务凭据格式为 `app_<credential-uuid>.<43字符随机secret>`，运营凭据使用 `ops_` 前缀。
默认没有凭据或种子数据。开启本地 fixture 的方法见[运行手册](docs/runbook/identity-and-fixtures.md)。

测试页面与完整支付运行说明见[PR06 业务支付手册](docs/runbook/business-payments.md)。创建收银台不等于扣款成功，结果未知时查询原支付。
回调接收配置、处理语义与验证来源见[PR08 Webhook Inbox 手册](docs/runbook/webhook-inbox.md)。

商户通知配置、幂等履约与故障验证见 [PR09 商户通知手册](docs/runbook/merchant-notifications.md)。

资金流水导入、期初政策与异常重放见 [PR10 BT 导入手册](docs/runbook/bt-ingestion.md)。

后台恢复配置、保留窗口与人工调查边界见[PR07 恢复手册](docs/runbook/operation-recovery.md)。真实 Stripe 的无 ID 未知创建默认不自动重发；Fake 的窗口实验不等于真实账户验证。

## 运行角色

| 角色 | profile | 默认端口 | 职责 | 不做什么 |
|---|---|---|---|---|
| API | `api` | 8080 | HTTP 业务入口，只做短事务 | 不跑后台恢复循环 |
| Worker | `worker` | 8081 | 后台执行、租约、恢复 | **不暴露业务写入口** |

两者是同一份构建产物、同一套迁移、同一个事务域。理由见 [ADR-0001](docs/adr/0001-modular-monolith-api-worker.md)。

## 模块包

`src/main/java/com/paymentsledger/` 下的模块包及其首次实现的 PR：

| 包 | PR | 职责 |
|---|---|---|
| `identity` | PR02 | 可信身份与账户范围 |
| `ledger` | PR03 | 不可变复式账本（唯一过账入口） |
| `provider` | PR04 / PR05 | Provider 端口、Fake、真实 Stripe 适配器 |
| `payment` | PR06 | 业务订单与支付 |
| `notification` | PR09 | Outbox / Kafka / 商户通知 |
| `financialingestion` | PR10 | BT 导入与资金入账 |
| `refund` | PR11 | 退款额度与恢复 |
| `revenueshare` | PR12 | 内部分润 |
| `dispute` | PR13 | 争议 |
| `payout` | PR14 | Payout 与模拟银行 |
| `reconciliation` | PR15 | 对账与夜间检查 |
| `ops` | PR16 | 运营 API 与审批 |

> 规格中写作 `revenue_share` / `financial_ingestion` 的两个模块，在代码中采用 Java 包命名惯例
> （全小写、无下划线）写作 `revenueshare` / `financialingestion`。

## 数据库角色分离

应用运行角色与迁移角色是两个不同的数据库角色。账本事件、凭证及分录对运行角色保持 INSERT-only；封存后连追加分录也会拒绝。
余额投影只通过内部过账服务调用的受控数据库函数更新，运行角色不能直接改余额。
PR02 身份与配置表对默认运行角色 `payments_app` 仅授予 SELECT；配置写入使用受控迁移角色。
详见 [docs/runbook/database-roles.md](docs/runbook/database-roles.md)。

## 文档

| 入口 | 内容 |
|---|---|
| [docs/product/](docs/product/) | PRD、路线图、验收矩阵、PR 提示词 |
| [docs/spec/requirements-map.md](docs/spec/requirements-map.md) | 需求 ↔ 不变量 ↔ 故障 ↔ 验收 ↔ PR 双向映射 |
| [docs/contracts/](docs/contracts/) | OpenAPI、AccountScope、Provider 端口 |
| [docs/state-machines/](docs/state-machines/) | 支付、退款、争议、payout、操作 |
| [docs/ledger/](docs/ledger/) | 科目、七项不变量、手算示例 |
| [docs/adr/](docs/adr/README.md) | 架构决策记录 |
| [docs/spec/version-and-capability-matrix.md](docs/spec/version-and-capability-matrix.md) | 版本与测试账户能力（含待验证项） |

## 安全边界

- 仅 Fake 或 Stripe 测试环境；启动时拒绝 live 凭据。
- 使用托管收银入口，后端**不收卡号**。
- API key、client secret、完整 token、卡数据、银行敏感字段**不进日志**。
- 密钥与 `.env` 不进版本库。
