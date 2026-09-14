# 运行说明：本地开发（Windows 与 Unix）

版本：PR01 · 状态：骨架阶段，仅健康检查可用

关联：[README](../../README.md) · [数据库角色](database-roles.md) · [ADR-0001 运行角色](../adr/0001-modular-monolith-api-worker.md) · [版本矩阵](../spec/version-and-capability-matrix.md)

## 1. 先决条件

| 组件 | 要求 | 检查命令 |
|---|---|---|
| JDK | **21**（基线，非候选项） | `java -version` |
| Docker | Compose v2 | `docker compose version` |
| Maven | 不需要预装 | 用仓库内 Wrapper |

### JDK 21 安装

本项目通过 `JAVA_HOME` 指定 JDK，**不要求**你改系统默认 Java。

**macOS**

```bash
# 方式一：Homebrew cask（需要输入管理员密码）
brew install --cask corretto@21

# 方式二：直接解压到用户目录（不需要 sudo）
mkdir -p ~/.jdks && cd ~/.jdks
curl -LO https://corretto.aws/downloads/latest/amazon-corretto-21-aarch64-macos-jdk.tar.gz
tar xzf amazon-corretto-21-aarch64-macos-jdk.tar.gz
export JAVA_HOME=~/.jdks/amazon-corretto-21.jdk/Contents/Home
```

> Intel Mac 把 URL 中的 `aarch64` 换成 `x64`。

**Linux**

```bash
# Debian/Ubuntu 示例
sudo apt-get install -y openjdk-21-jdk
# 或解压 Corretto tarball 并设置 JAVA_HOME
```

**Windows**

```powershell
winget install Amazon.Corretto.21.JDK
# 或从 https://docs.aws.amazon.com/corretto/ 下载 MSI
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk21.0.x_y"
```

验证：

```bash
"$JAVA_HOME/bin/java" -version     # Unix
"%JAVA_HOME%\bin\java" -version    # Windows cmd
```

## 2. Maven Wrapper

仓库使用 **only-script** 模式的 Maven Wrapper：`.mvn/wrapper/maven-wrapper.properties` 指定
Maven 发行版 URL，脚本首次运行时自动下载，**仓库内不含任何 jar 二进制**。

| 平台 | 命令 |
|---|---|
| Unix / macOS | `./mvnw ...` |
| Windows | `mvnw.cmd ...` |

首次执行会下载 Maven 与依赖，耗时取决于网络。

## 3. 常用命令

| 目的 | Unix / macOS | Windows |
|---|---|---|
| 编译 + 单元/上下文测试 | `./mvnw test` | `mvnw.cmd test` |
| 全量（含集成测试，需 Docker） | `./mvnw verify` | `mvnw.cmd verify` |
| 打包 | `./mvnw clean package -DskipTests` | `mvnw.cmd clean package -DskipTests` |
| 启动 API 角色 | `./mvnw spring-boot:run -Dspring-boot.run.profiles=api` | `mvnw.cmd spring-boot:run -D"spring-boot.run.profiles=api"` |
| 启动 Worker 角色 | `./mvnw spring-boot:run -Dspring-boot.run.profiles=worker` | `mvnw.cmd spring-boot:run -D"spring-boot.run.profiles=worker"` |

> Windows 下 `-D` 参数含点号时建议加引号：`-D"spring-boot.run.profiles=api"`。

## 4. 基础设施

```bash
cp .env.example .env      # Unix
copy .env.example .env    # Windows cmd

docker compose up -d
docker compose ps         # 等待 postgres / redis / kafka 全部 healthy
```

| 服务 | 端口 | 健康检查 | 本 PR 是否连接 |
|---|---|---|---|
| PostgreSQL | 5432 | `pg_isready` | ✅ 连接 + 迁移 |
| Redis | 6379 | `redis-cli ping` | ❌ 客户端 PR07 接入 |
| Kafka | 9092 | `kafka-broker-api-versions.sh` | ❌ 客户端 PR09 接入 |

Redis 与 Kafka 现在就起，是为了让后续 PR 无需改动基础设施；PR01 的应用**不建立**这两者的客户端连接（`pom.xml` 中刻意未引入对应 starter）。

停止与清理：

```bash
docker compose down        # 停容器，保留数据卷
docker compose down -v     # 同时删除本项目的命名卷（pl-postgres-data / pl-kafka-data）
```

> `down -v` 只影响本 Compose 项目的命名卷，不会碰你机器上其它项目的数据。

## 5. 验证骨架

```bash
curl -s localhost:8080/actuator/health    # API
curl -s localhost:8081/actuator/health    # Worker
```

期望 `{"status":"UP"}`。健康端点刻意不展示组件细节（`show-details: never`），避免泄露依赖拓扑与版本。

验证统一错误契约：

```bash
curl -i -s localhost:8080/v1/payments/nope
```

期望 404，响应体含 `code` / `retryable` / `requestId`，且响应头有 `X-Request-Id`。

## 6. 运行角色必须显式指定

不带 profile 启动会**立即失败**：

```text
Exactly one run role profile must be active, got [] from active profiles [].
Start with --spring.profiles.active=api or =worker.
```

这是刻意的。一个既是 API 又是 Worker 的进程会让崩溃域、扩缩容边界与故障注入点（F-06）全部失去意义。

## 7. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `release version 21 not supported` | `JAVA_HOME` 指向 JDK 17 或更低 | 设置 `JAVA_HOME` 指向 JDK 21 后重试 |
| 集成测试报找不到 Docker | Docker Desktop 未启动 | 启动 Docker，`docker info` 应返回 Server Version |
| Flyway 报权限不足 | 用了应用角色跑迁移 | 迁移必须用 `payments_migrator`，见[数据库角色](database-roles.md) |
| 端口被占用 | 8080 / 8081 / 5432 冲突 | 在 `.env` 改 `API_PORT` / `WORKER_PORT` / `DB_PORT` |
| Kafka 容器反复重启 | 数据卷残留旧 cluster id | `docker compose down -v` 后重建（只影响本项目卷） |

## 8. 本 PR 的边界

PR01 **只有**骨架：

- ✅ 工程结构、运行角色、统一错误响应、requestId、Clock、日志脱敏基线
- ✅ Compose 基础设施、Flyway baseline、Dockerfile、CI
- ❌ 无业务表（payment / refund / journal 等一律不存在）
- ❌ 无 Stripe SDK、无资金调用
- ❌ 除 `GET /actuator/health` 外无任何对外接口

业务能力从 PR02 起逐步交付，归属见[需求映射](../spec/requirements-map.md)。
