# 版本与测试账户能力矩阵

版本：PR00 · 日期：2026-09-10 · 状态：**候选清单，全部待验证**

关联：[需求映射](requirements-map.md) · [Provider 端口](../contracts/provider.md) · [路线图 §1](../product/payment-platform-roadmap.md) · [验收 §1](../product/payment-platform-acceptance.md)

## 0. 本文件的诚实边界

> **本次 PR00 未联网核实任何版本号，也未访问任何 Stripe 测试账户。**
>
> 下表中**没有一项**是"已验证"。所有条目只有三种来源：
> - `BASELINE`：由 PRD/路线图直接规定的工程基线（如 Java 21）
> - `CANDIDATE_LINE`：候选版本**线**（主版本系列），精确版本由 PR01 实际解析依赖后记录
> - `TBD`：连候选线都未确定，必须由负责 PR 调查后填写
>
> **禁止**在本文件写入"最新版本"字样。"最新"不是版本号，且会随时间失效。
> **禁止**为测试账户能力预填 `PASS` 或"支持"。

## 1. 运行时与框架

| 组件 | 候选 | 来源 | 状态 | 验证 PR | 备注 |
|---|---|---|---|---|---|
| Java | **21（LTS）** | `BASELINE`（PRD §3.4、路线图 §1） | 已冻结为基线，**具体发行版与补丁号待定** | PR01 | 需记录发行版（Temurin / Corretto / Zulu 等）与完整版本串 |
| Spring Boot | 3.x 线 | `CANDIDATE_LINE` | 待验证 | PR01 | 精确版本由 PR01 解析依赖树后锁定；须与 Java 21 兼容 |
| Maven Wrapper | 随 PR01 生成 | `BASELINE` | 待验证 | PR01 | 必须同时提供 `mvnw` 与 `mvnw.cmd`（Windows/Unix） |
| PostgreSQL | 16.x 线 | `CANDIDATE_LINE` | 待验证 | PR01 | 需支持 `DEFERRABLE INITIALLY DEFERRED` 约束触发器、`num_nonnulls()`、`gen_random_uuid()` |
| Flyway | 10.x 或 11.x 线 | `CANDIDATE_LINE` | 待验证 | PR01 | 须与所选 PostgreSQL 主版本兼容；迁移角色与运行角色分离 |
| Apache Kafka | 3.x 线 | `CANDIDATE_LINE` | 待验证 | PR01 | 仅作消息传递，**不担任账本** |
| Redis | 7.x 线 | `CANDIDATE_LINE` | 待验证 | PR01 | 仅缓存与限流，**不是**资金或幂等正确性依据 |
| Testcontainers | `TBD` | — | 待验证 | PR01 | 真实 PostgreSQL / Kafka 集成测试所需 |
| 属性测试库 | jqwik（候选） | `CANDIDATE_LINE` | 待验证 | PR01 / PR18 | 验收 §7.1 记为"候选工具"，版本在 PR01 固定 |

**PR01 必须记录的内容**：实际解析到的完整版本串、依赖树冲突与解决方式、锁定方式（`dependencyManagement` / BOM）、以及 `mvn -v` / `java -version` / 容器镜像 tag 的真实输出。

## 2. Stripe 相关

| 项 | 候选 | 状态 | 验证 PR | 说明 |
|---|---|---|---|---|
| `stripe-java` SDK 版本 | `TBD` | **待验证** | PR01 引入 / PR05 冻结 | 不预填版本号。PR05 必须记录实际生效版本 |
| Stripe API 版本（pinned） | `TBD` | **待验证** | PR05 | 必须是**显式固定**的日期版本串，不使用账户默认版本随时间漂移 |
| Webhook endpoint API 版本 | `TBD` | **待验证** | PR05 | 可能与 SDK 请求版本不同，需分别记录 |
| 幂等键实际保留窗口 | `TBD` | **待验证** | PR05 | 官方语义已读（[S2](https://docs.stripe.com/api/idempotent_requests)）；**在实测前按"不可依赖永久去重"设计** |
| 测试账户 ID（脱敏） | `TBD` | 待填 | PR05 | 只记录脱敏引用，不记录密钥 |
| 运行币种 | USD（默认） | 待确认 | PR02 / PR05 | JPY 作为独立零小数 fixture，不替换全部示例 |

**版本冻结的含义**：PR05 完成后，`stripe-java` 版本、Stripe API 版本、endpoint 版本三者一并记入本表并标为 `FROZEN`，后续变更需新 PR 并重跑契约测试。

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
| V1 | 全部运行时/框架精确版本 | PR01 | 工程门槛；所有后续 AC |
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
