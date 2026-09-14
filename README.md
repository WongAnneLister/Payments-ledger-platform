# Payments Ledger Platform

Stripe 支付网关与复式账本。模块化单体，API / Worker 分角色运行，共享同一个 PostgreSQL 事务域。

**当前状态：PR01 工程骨架。** 没有业务功能，没有 Stripe 调用，只有健康检查。
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

`GET /actuator/health` 是 PR01 **唯一**对外暴露的接口。

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
| `messaging` | PR09 | Outbox / Kafka / 商户通知 |
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

应用运行角色与迁移角色是两个不同的数据库角色。账本表将保持 INSERT-only。
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
