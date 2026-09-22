# 版本与测试账户能力矩阵

版本：PR05 · 日期：2026-09-22 · 状态：**运行时已锁定；Stripe SDK/API 配对已本地验证，真实账户与 endpoint 验证待配置**

关联：[需求映射](requirements-map.md) · [Provider 端口](../contracts/provider.md) · [路线图 §1](../product/payment-platform-roadmap.md) · [验收 §1](../product/payment-platform-acceptance.md)

## 0. 本文件的诚实边界

状态来源分四种：

- `BASELINE`：由 PRD/路线图直接规定的工程基线（如 Java 21）
- `LOCKED`：**PR01 已实际解析依赖并跑通构建与测试**，版本串来自真实命令输出
- `CANDIDATE_LINE`：候选版本**线**，精确版本待负责 PR 记录
- `TBD`：连候选线都未确定，必须由负责 PR 调查后填写

> **PR01 的验证边界**：运行时、框架、基础设施镜像已在本机实测锁定（§1）。
> **Stripe SDK/API 的本地契约与真实账户能力分开记录**（§2、§3）。本地通过不解锁 R0 真实门槛。
>
> **禁止**在本文件写入"最新版本"字样——"最新"不是版本号，且会随时间失效。
> **禁止**为测试账户能力预填 `PASS` 或"支持"。

### PR01 验证环境

| 项 | 值 |
|---|---|
| 日期 | 2026-09-11 |
| 宿主 | macOS（Darwin 22.6.0）、arm64 |
| JDK | Amazon Corretto `21.0.12.9.1`（`openjdk version "21.0.12.1" 2026-08-18 LTS`） |
| Maven | `3.9.16`（经 Maven Wrapper `only-script` 模式自举） |
| Docker | Server `27.4.0`；Compose `v2.31.0-desktop.2` |
| 构建结果 | `./mvnw -B verify` → **BUILD SUCCESS** |
| 测试结果 | 单元/上下文 14 项 + 集成 6 项 = **20 项全部通过，0 失败** |

> JDK 采用官方 tarball 解压到用户目录（`~/.jdks/`）的方式安装，
> 不改动系统默认 JDK，也不需要 sudo。

## 1. 运行时与框架

| 组件 | 锁定版本 | 来源 | 状态 | 证据 | 备注 |
|---|---|---|---|---|---|
| Java | **Corretto 21.0.12.9.1**（`21.0.12.1+9-LTS`） | `BASELINE` + `LOCKED` | ✅ 已验证 | `java -version`；20 项测试通过 | 基线为 Java 21；本机发行版为 Amazon Corretto |
| Maven | **3.9.16** | `LOCKED` | ✅ 已验证 | `./mvnw -N help:evaluate` → `3.9.16` | 经 Wrapper 自举，无需预装 |
| Maven Wrapper | **3.3.4**（`only-script`） | `LOCKED` | ✅ 已验证 | `mvnw` + `mvnw.cmd` 均已交付 | **仓库内不含 jar 二进制** |
| Spring Boot | **3.5.16** | `LOCKED` | ✅ 已验证 | 上下文加载成功；Tomcat 10.1.55 | `spring-boot-starter-parent` |
| PostgreSQL（服务端） | **16.14**（镜像 `postgres:16`） | `LOCKED` | ✅ 已验证 | Flyway 日志 `(PostgreSQL 16.14)`；Compose healthy | 支持 `DEFERRABLE` 约束触发器、`num_nonnulls()`、`gen_random_uuid()`（PR03 将实际使用） |
| PostgreSQL JDBC | **42.7.13** | `LOCKED` | ✅ 已验证 | 集成测试连接成功 | `runtime` scope |
| Flyway | **11.20.3** | `LOCKED` | ✅ 已验证 | 空库迁移 + 二次迁移幂等均通过 | `flyway-core` + `flyway-database-postgresql` |
| Apache Kafka | **3.9.0**（镜像 `apache/kafka:3.9.0`） | `LOCKED`（基础设施） | ✅ 容器 healthy | `docker compose ps` | **客户端未接入**，PR09 引入 `spring-kafka` |
| Redis | **7-alpine** | `LOCKED`（基础设施） | ✅ 容器 healthy | `docker compose ps` | **客户端未接入**，PR07 引入 |
| Testcontainers | **1.21.4** | `LOCKED` | ✅ 已验证 | 3 个 IT 均用一次性容器跑通 | 仅 `test` scope |
| 属性测试库 | jqwik | `CANDIDATE_LINE` | ⏳ 待验证 | — | PR18 才实际引入，届时锁定版本 |

### 1.1 刻意未引入的依赖

为避免「未接通的组件」与启动期无谓的连接尝试，以下依赖 PR01 **不引入**：

| 依赖 | 引入 PR | 理由 |
|---|---|---|
| `spring-kafka` | PR09 | Outbox / 消费者接线时才需要 |
| `spring-boot-starter-data-redis` | PR07 | 限流 / 缓存时才需要 |
| `stripe-java` | PR05 | 真实适配器时才需要 |

基础设施（Kafka / Redis 容器）已在 Compose 中就绪，后续 PR 无需改动基础设施即可接入。

### 1.2 依赖解析说明

所有版本由 PR01 查询 Maven Central 的 `maven-metadata.xml` 实际获得，并经 `./mvnw -B verify` 跑通。
未出现需要手工干预的依赖树冲突；第三方版本除显式声明的四个属性
（`flyway.version`、`postgresql.version`、`testcontainers.version` 与父 POM）外，
均由 `spring-boot-starter-parent` 的 BOM 统一管理。

## 2. Stripe 相关

| 项 | 候选 | 状态 | 验证 PR | 说明 |
|---|---|---|---|---|
| `stripe-java` SDK 版本 | `33.3.0` | **LOCAL_LOCKED** | PR05 | Maven 实际解析；真实 SDK HTTP fixture 测试，非真实账户结论 |
| Stripe API 版本（pinned） | `2026-07-29.dahlia` | **LOCAL_LOCKED / 远端 BLOCKED** | PR05 | SDK 33.3.0 生成版本，测试验证请求头；不使用账户默认版本 |
| Webhook endpoint API 版本 | 期望 `2026-07-29.dahlia`；远端 `TBD` | **BLOCKED** | PR05 | 本地验签强制精确版本；尚无远端 endpoint 记录 |
| 幂等键实际保留窗口 | 实测 `TBD` | **BLOCKED；官方契约已核对** | PR05 | [官方契约](https://docs.stripe.com/api/idempotent_requests)：至少 24 小时后可清理；非精确 TTL，不启用未验证的自动恢复窗口 |
| 测试账户 ID（脱敏） | `TBD` | 待填 | PR05 | 只记录脱敏引用，不记录密钥 |
| 运行币种 | USD（默认） | 待确认 | PR02 / PR05 | JPY 作为独立零小数 fixture，不替换全部示例 |

**版本冻结的含义**：仅本地 LOCAL_LOCKED 不代表远端 FROZEN。PR05 真实验证完成后，`stripe-java` 版本、Stripe API 版本、endpoint 版本三者一并记入本表并标为 `FROZEN`，后续变更需新 PR 并重跑契约测试。

## 3. 测试账户能力矩阵

> **以下每一项当前都是"未知"。** 不得因为"Stripe 文档说有这个功能"就标为支持——
> 本项目关心的是**这个测试账户在冻结版本下实际能否触发**。

| 能力 | 用途 | 当前状态 | 验证 PR | 不可用时的替代 |
|---|---|---|---|---|
| Checkout Session 创建 / 查询 | PR06 最小客户入口 | **未知** | PR05 | 无替代；不可用则 R0 契约门槛 `BLOCKED` |
| Checkout Session `expire` | FR-PAY-03 取消语义 | **未知** | PR05 | Fake 覆盖，真实 AC 标 `BLOCKED` |
| PI ↔ Charge 关联、多 attempt | AC-V1-06 | **未知** | PR05 | Fake 覆盖 |
| 自动 capture | 本期唯一支付方式 | **未知** | PR05 | 无替代 |
| `payment_intent_data.metadata` 显式传递 | FR-PAY-01 | **未知** | PR05 | **必须实测核验，不假设自动继承** |
| 卡拒付 / 需认证测试卡 | AC-V1-06 | **未知** | PR05 | Fake 覆盖 |
| Refund 创建 / 查询 | PR11 | **未知** | PR05 | Fake 覆盖 |
| 退款失败与失败返还 BT | AC-V1-21 | **未知** | PR05 / PR11 | **很可能 fixture-only** |
| Balance Transaction 分页读取 | PR10 核心 | **未知** | PR05 | 无替代；不可用则 I-05 无法真实验证 |
| BT `available_on` / pending→available | AC-V1-30 | **未知** | PR05 | Fake 覆盖 |
| **Dispute 触发**（测试争议） | PR13 全部资金路径 | **未知，很可能受限** | PR05 / PR13 | `fixture-only`，相关 AC **不得 PASS** |
| Dispute won / lost / late win | AC-V1-24、25 | **未知，很可能受限** | PR13 | `fixture-only` |
| **Payout 摄入 / 查询** | PR14 必做 | **未知** | PR05 / PR14 | `fixture-only` |
| 主动发起 test payout | PR14 可选 | **未知** | PR14 | 缺该能力**不虚构实测**，标注可选未覆盖 |
| Payout 失败与 `failure_balance_transaction` | AC-V1-27 | **未知，很可能受限** | PR14 | `fixture-only` |
| Balance API 快照 | PR15 | **未知** | PR05 | — |
| **报告 / 历史期末证据**（`ReportPort`） | I-05 第三份独立证据 | **未知，可能需额外开通** | PR05 / PR15 | 受控静止区间快照；否则运行 `INCOMPLETE` |
| Webhook 真实接收（公网可达） | AC-V1-11 | **未知** | PR05 / PR08 | 本地签名 fixture，**必须标注来源区别** |

### 3.1 记录规则

| 结论 | 含义 | 对 AC 的影响 |
|---|---|---|
| `VERIFIED` | 在冻结版本 + 该测试账户上实际跑通，有脱敏证据 | 相关 AC 可评 |
| `BLOCKED` | 账户/环境不支持，记录原因与替代覆盖 | 真实契约 AC **不得 PASS** |
| `FIXTURE_ONLY` | 仅用 Fake 或 fixture 覆盖 | 模拟用例可单独 `PASS`，**不得**合并声称真实能力已验证 |

**PR05 报告必须给出"实测 / fixture 矩阵"**，逐项标注上述三种结论之一。

## 4. 不允许出现的表述

| ❌ 禁止 | ✅ 应写成 |
|---|---|
| "使用最新版本的 Spring Boot" | "Spring Boot 3.x 线，精确版本 PR01 记录" |
| "Stripe 支持争议测试，所以已覆盖" | "争议触发能力未知，PR13 若受限则 `fixture-only`，AC-V1-24 不得 PASS" |
| "幂等键保留 24 小时" | "保留窗口 `TBD`，PR05 实测或引用官方契约后填写" |
| "已完成 Stripe 集成" | "PR05 已验证 X/Y/Z 契约；A/B 能力 `BLOCKED`，原因与替代见报告" |
| "本地模拟压测通过" | "PR04 模拟器实验；**未**向 Stripe 测试环境发送负载" |

## 5. 待办登记

| # | 待验证项 | 负责 PR | 阻塞什么 |
|---|---|---|---|
| ~~V1~~ | ~~全部运行时/框架精确版本~~ | ~~PR01~~ | **✅ 已完成（2026-09-11），见 §1** |
| V2 | `stripe-java` + Stripe API 版本冻结 | PR05 | AC-V1-05、06、07 |
| V3 | 幂等键实际保留窗口 | PR05 | AC-V1-09 的真实契约部分 |
| V4 | Checkout create/query/expire 实测 | PR05 | AC-V1-05、07 |
| V5 | metadata 显式传递与传播核验 | PR05 | AC-V1-05 |
| V6 | BT 分页与字段实测 | PR05 | AC-V1-16、18、I-05 |
| V7 | Dispute 能力 | PR05 / PR13 | AC-V1-24、25 |
| V8 | Payout 能力 | PR05 / PR14 | AC-V1-26、27 |
| V9 | 报告/历史期末证据能力 | PR05 / PR15 | AC-V1-29、I-05 第三份证据 |
| V10 | Webhook 真实接收路径 | PR05 / PR08 | AC-V1-11 的真实来源标注 |

**R0 阶段门槛**：PR05 账户能力不足时，可继续 Fake 模式的独立开发，但 **R0 的真实契约门槛保持 `BLOCKED`**，不能把阶段标为全通过（PRD §18）。

## 6. PR05 当前验证边界

本地：Checkout/refund 参数及对象映射、全部 Charge attempts、错误分类与未知结果、财务分页与余额域、原始字节签名使用真实 SDK 加确定性 HTTP/签名 fixture。来源标记 `LOCAL_SDK_HTTP_FIXTURE` / `LOCAL_SIGNATURE_FIXTURE`；PR04 仍是 `PROVIDER_SIMULATOR`。

真实账户：当前没有完成一次账户契约运行，§3 所有真实能力仍未知，R0 为 BLOCKED；验收 ID 不得据本地测试变为 PASS。ReportPort 历史报告与非 PAYMENTS 域余额快照尚未验证契约，明确返回 CAPABILITY_UNAVAILABLE。公开 ReportRun 的 succeeded 状态不能代替下载并校验历史财务报告。

运行方法与分项证据见[真实契约测试手册](../runbook/stripe-contract-check.md)；本次本地验证结果见[PR05 证据](../evidence/pr05/README.md)。版本依据：[Stripe 33.3.0 官方发布](https://github.com/stripe/stripe-java/releases/tag/v33.3.0)，及对应源码 `ApiVersion.CURRENT`。
