# 版本与测试账户能力矩阵

版本：PR05 · 日期：2026-09-23 · 状态：**运行时已锁定；Stripe SDK/API 配对与真实 Checkout 基础契约已验证，单笔成功付款、退款资金链路与真实 endpoint 接收已验证；异常场景仍待验证**

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
| Apache Kafka | **3.9.0**（镜像 `apache/kafka:3.9.0`） | `LOCKED`（基础设施） | ✅ 容器 healthy | `docker compose ps` | PR09 接入客户端；本地故障测试使用相同 `apache/kafka:3.9.0` 镜像 |
| Spring Kafka | **3.3.16** | Boot BOM | LOCAL_LOCKED | PR09 实际 Kafka 测试 | 仅显式启用的 Worker 连接 |
| Kafka clients | **3.9.2** | Boot BOM | LOCAL_LOCKED | PR09 实际 Kafka 测试 | broker 镜像仍为 3.9.0 |
| Apache HttpClient / HttpCore | **5.5.2 / 5.3.6** | Boot BOM | LOCAL_LOCKED | PR09 真实 loopback HTTP 测试 | 固定验证后的 DNS 地址，禁用重定向 |
| Redis | **7-alpine** | `LOCKED`（基础设施） | ✅ 容器 healthy | `docker compose ps` | **客户端未接入**，PR07 引入 |
| Testcontainers | **1.21.4** | `LOCKED` | ✅ 已验证 | 3 个 IT 均用一次性容器跑通 | 仅 `test` scope |
| 属性生成与收缩 | **PR18-1**（仓库测试工具） | `LOCAL_LOCKED` | 本机验证 | [PR18 证据](../evidence/pr18/README.md) | JUnit + 确定性语法生成器、依赖保持删除收缩；未引入 jqwik，默认与扩展预算分别报告 |

### 1.1 PR01 时的依赖接入安排（历史）

为避免「未接通的组件」与启动期无谓的连接尝试，以下依赖 PR01 **不引入**；PR05 已接入 Stripe，PR09 已接入 Spring Kafka：

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
| `stripe-java` SDK 版本 | `33.3.0` | **VERIFIED（基础契约）** | PR05 | Maven 实际解析，真实 Checkout create/query/expire 已通过 |
| Stripe API 版本（pinned） | `2026-07-29.dahlia` | **VERIFIED（基础契约）** | PR05 | 真实请求通过；单笔成功付款/退款及 BT 链路已验证，异常路径仍待验证 |
| Webhook endpoint API 版本 | 本次专用 `2026-07-29.dahlia` | **VERIFIED（成功路径）** | PR05 | 6 个真实关联事件验签通过；临时端点已删除，既有端点未修改 |
| 幂等键实际保留窗口 | 实测 `TBD` | **BLOCKED；官方契约已核对** | PR05 | [官方契约](https://docs.stripe.com/api/idempotent_requests)：至少 24 小时后可清理；非精确 TTL，不启用未验证的自动恢复窗口 |
| 测试账户 ID（脱敏） | SHA-256 `cf3f54f8…de8cf58` | **VERIFIED** | PR05 | 完整哈希见 real/verification.json；凭据通过 /v1/account 绑定核验 |
| 运行币种 | Checkout USD；当前 Balance JPY | **分别 VERIFIED** | PR05 | 测试会话金额 USD 1.00；账户余额实际为 JPY，本次 USD 1 付款对应 JPY 157 结算流水，非本地账本入账 |

**版本冻结的含义**：仅本地 LOCAL_LOCKED 不代表远端 FROZEN。PR05 真实验证完成后，`stripe-java` 版本、Stripe API 版本、endpoint 版本三者一并记入本表并标为 `FROZEN`，后续变更需新 PR 并重跑契约测试。

## 3. 测试账户能力矩阵

> **按每项实际证据区分 VERIFIED 与未知/BLOCKED。** 不得因为"Stripe 文档说有这个功能"就标为支持——
> 本项目关心的是**这个测试账户在冻结版本下实际能否触发**。

| 能力 | 用途 | 当前状态 | 验证 PR | 不可用时的替代 |
|---|---|---|---|---|
| Checkout Session 创建 / 查询 | PR06 最小客户入口 | **VERIFIED：两次未付款 smoke + 一次完成付款** | PR05 | smoke 见 PR05 real/；成功付款见 lifecycle/ |
| Checkout Session `expire` | FR-PAY-03 取消语义 | **VERIFIED：OPEN→EXPIRED** | PR05 | 取消与支付竞争仍 BLOCKED |
| PI ↔ Charge 关联、多 attempt | AC-V1-06 | **单次成功关联 VERIFIED；多 attempt 未验证** | PR05 | Fake 覆盖 |
| 自动 capture | 本期唯一支付方式 | **VERIFIED：PI automatic + Charge captured** | PR05 | 无替代 |
| `payment_intent_data.metadata` 显式传递 | FR-PAY-01 | **VERIFIED：分别核对 Session/PI 标记** | PR05 | **必须实测核验，不假设自动继承** |
| 卡拒付 / 需认证测试卡 | AC-V1-06 | **未知** | PR05 | Fake 覆盖 |
| Refund 创建 / 查询 | PR11 | **VERIFIED：USD 1 全额成功退款及 BT** | PR05 | Fake 覆盖 |
| 退款失败与失败返还 BT | AC-V1-21 | **未知** | PR05 / PR11 | **很可能 fixture-only** |
| Balance Transaction 分页读取 | PR10 核心 | **两个 BT 详情 VERIFIED；分页未验证** | PR05 | 无替代；不可用则 I-05 无法真实验证 |
| BT `available_on` / pending→available | AC-V1-30 | **未知** | PR05 | Fake 覆盖 |
| **Dispute 触发**（测试争议） | PR13 全部资金路径 | **未知，很可能受限** | PR05 / PR13 | `fixture-only`，相关 AC **不得 PASS** |
| Dispute won / lost / late win | AC-V1-24、25 | **未知，很可能受限** | PR13 | `fixture-only` |
| **Payout 摄入 / 查询** | PR14 必做 | **未知** | PR05 / PR14 | `fixture-only` |
| 主动发起 test payout | PR14 可选 | **未知** | PR14 | 缺该能力**不虚构实测**，标注可选未覆盖 |
| Payout 失败与 `failure_balance_transaction` | AC-V1-27 | **未知，很可能受限** | PR14 | `fixture-only` |
| Balance API 快照 | PR15 | **VERIFIED：当前 JPY / PAYMENTS** | PR05 | USD 当前无余额项，不据此推断任何历史余额 |
| **报告 / 历史期末证据**（`ReportPort`） | I-05 第三份独立证据 | **未知，可能需额外开通** | PR05 / PR15 | 受控静止区间快照；否则运行 `INCOMPLETE` |
| Webhook 真实接收（公网可达） | AC-V1-11 | **VERIFIED：6 个关联事件；业务 Inbox 尚未实现** | PR05 / PR08 | 真实来源见 lifecycle/；本地 fixture 另行标注 |

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
| V4 | Checkout create/query/expire 实测已通过；取消竞争待验证 | PR05 | AC-V1-05、07 的剩余部分 |
| V5 | Session / PI metadata 已分别实测通过 | PR05 | AC-V1-05 |
| V6 | BT 分页与字段实测 | PR05 | AC-V1-16、18、I-05 |
| V7 | Dispute 能力 | PR05 / PR13 | AC-V1-24、25 |
| V8 | Payout 能力 | PR05 / PR14 | AC-V1-26、27 |
| V9 | 报告/历史期末证据能力 | PR05 / PR15 | AC-V1-29、I-05 第三份证据 |
| V10 | Webhook 真实接收路径 | PR05 / PR08 | AC-V1-11 的真实来源标注 |

**R0 阶段门槛**：PR05 账户能力不足时，可继续 Fake 模式的独立开发，但 **R0 的真实契约门槛保持 `BLOCKED`**，不能把阶段标为全通过（PRD §18）。

## 6. PR05 当前验证边界

本地：Checkout/refund 参数及对象映射、全部 Charge attempts、错误分类与未知结果、财务分页与余额域、原始字节签名使用真实 SDK 加确定性 HTTP/签名 fixture。来源标记 `LOCAL_SDK_HTTP_FIXTURE` / `LOCAL_SIGNATURE_FIXTURE`；PR04 仍是 `PROVIDER_SIMULATOR`。

真实账户：2026-09-23 已完成两次基础 smoke，以及一笔真实托管 Checkout 测试付款和全额退款。付款/退款 BT、automatic capture、Session/PI metadata、专用固定版本端点的 6 个关联回调及篡改拒绝均已验证，见[联调证据](../evidence/pr05/lifecycle/README.md)。临时端点已删除，既有端点未修改。争议、payout、多 attempt、异常路径、BT 分页及业务入账仍未验证。R0 仍为 BLOCKED；不能据基础 smoke 把完整验收 ID 标为 PASS。ReportPort 历史报告与非 PAYMENTS 域余额快照尚未验证契约，明确返回 CAPABILITY_UNAVAILABLE。公开 ReportRun 的 succeeded 状态不能代替下载并校验历史财务报告。

运行方法与分项证据见[真实契约测试手册](../runbook/stripe-contract-check.md)；本次本地验证结果见[PR05 证据](../evidence/pr05/README.md)。版本依据：[Stripe 33.3.0 官方发布](https://github.com/stripe/stripe-java/releases/tag/v33.3.0)，及对应源码 `ApiVersion.CURRENT`。
