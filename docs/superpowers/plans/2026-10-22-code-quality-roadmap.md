# 代码质量改造路线图（2026-10-22 起）

> 目标：让每个模块都符合"领域规则 / 仓储 / 服务"三层结构，SQL 只出现在 `repository` 包里，资金规则可以脱离数据库单独测试；同时清掉可读性审计里剩下的结构问题。
> 原则：每一步都不改业务行为，并且能用 CI 证明这一点。
> 状态用复选框跟踪，每完成一项就在这里勾掉并写上提交号。

## 1. 已经完成的

| 阶段 | 内容 | 主要提交 |
|---|---|---|
| 格式化 | Spotless + palantir-java-format，CI 强制检查；超过 120 字符的行从 1521 降到 201 | PR #3 |
| SQL 文本块 | 950 条长 SQL 排成多行文本块，字节码对比 0 差异；文档行号链接加校验 | PR #3 |
| SQL 追踪工具 | `SqlTrace` 在 DataSource 层录制每条 SQL 和参数，用于重构前后对比 | 已合并 |
| bank 模块 | 拆成 domain / repository / service | 已合并 |
| payment 模块 | 支付意图、读模型、操作租约、服务商结果写回、退款预留、退款事实写回，共 6 轮 | 至 1af9831 |
| 白名单收尾 | `PaymentRepository`、`RefundRepository` 不再依赖 JDBC，移出 `NOT_YET_MIGRATED` | 0b2414e |

payment 模块的结果：`PaymentRepository` 从约 1055 行降到 337 行，`RefundRepository` 从 920 行降到 192 行，两者都不再含 SQL。拆出了 8 个规则类，配有 70 多个不需要数据库的单元测试。

## 2. 还剩下什么

### 2.1 分层迁移：白名单里还有 37 个类，约 1 万行

| 模块 | 类数 | 行数 | 主要的类 |
|---|---|---|---|
| ops（运营后台） | 3 | 2170 | `OpsQueryService` 1072 行，`OpsCommandService` 982 行 |
| reconciliation（对账） | 6 | 1934 | `ReconciliationChecks` 952 行 |
| financialingestion（财务数据导入） | 4 | 1026 | `FinancialImportService` 457 行 |
| payout（结算出款） | 4 | 891 | `PayoutService`、`PayoutIngestionService` |
| notification（商户通知） | 7 | 772 | 7 个小类，含一个测试用的模拟商户服务器 |
| payment（剩余部分） | 2 | 664 | `ReconciliationSourceRecovery` 555 行 |
| dispute（争议） | 2 | 638 | `DisputeService` 579 行 |
| webhook（回调收件箱） | 2 | 585 | `WebhookProcessor`、`WebhookInboxRepository` |
| observability（监控） | 1 | 392 | `ObservabilityService` |
| revenueshare（分账） | 1 | 387 | `RevenueShareService` |
| ledger（记账） | 3 | 361 | `LedgerPostingService`，资金核心 |
| identity（身份） | 2 | 308 | `IdentityRepository`、本地夹具初始化 |

### 2.2 审计第三期剩下的结构问题

| 问题 | 审计时 | 现在 | 说明 |
|---|---|---|---|
| 用 `row.get("列名")` 读弱类型 Map | 约 1050 处 | 737 处 | 分层迁移时顺带换成 record，大部分会随第 4 节消失 |
| 状态用字符串比较，如 `"CONFLICT".equals(...)` | 138 处 | 107 处 | 同上，迁移时换成枚举 |
| 超大类 | 2 个 Ops 类各约 1100 行 | 仍是 1072 和 982 行 | 在 ops 模块迁移时拆分 |
| 按 PR 编号命名的测试类 `Pr18*` / `Pr19*` / `Pr20*` | 31 个 | 31 个 | 要同步改 CI 脚本和证据文档里的引用 |
| `pr12_ceiling_guard` 这类数据库函数名、`lease_owner='pr07'` | — | 152 处 | 改函数名需要新的 Flyway 迁移，见第 7 节的决定事项 |
| 文档里指向源码行号的链接 | 72 个 | 31 个 | 每次挪代码都要重新对齐，容易悄悄指错 |

### 2.3 零散遗留

- `Pr18FailureIdentityTest` 在 Windows 上失败：AssertJ 的失败信息以 `System.lineSeparator()` 开头，Windows 上是 `\r\n`，测试写死了 `\n`。CI 在 Linux 上是绿的。
- 本地备份分支 `backup/before-email-rewrite`（10-16 改写提交作者前的历史）还没删。
- 审计时说过要加 `.git-blame-ignore-revs`，让 `git blame` 跳过全量格式化那几个提交，还没加。
- 重构用的对比工具（`trace_compare.py`、文档锚点重映射脚本）还放在本机临时目录，不在仓库里。换台机器就没了。
- `PaymentRepository.Claim` 里的 `operation` 和 `payment` 还是 `Map<String, Object>`，执行器和恢复协调器按列名读取。
- `PaymentRepository` 现在已经没有 SQL，只是服务门面，类名里的 "Repository" 会误导读者。

## 3. 工作方法（沿用 payment 模块验证过的流程）

每个模块分一到三轮，每轮：

1. **接上 SQL 追踪**：该模块的测试夹具若还没接 `SqlTrace`，先接上。目前只有 `PaymentCoreFixture`、`PayoutBankFixture` 和 Pr18 系列接了，其余模块都要补。
2. **推基线**：补一个特征测试（`*CharacterizationIT`），把这一轮要动的每个分支写下的行都钉住。不改生产代码，推上去让 CI 录下 SQL 轨迹。
3. **本地重构**：领域规则 → 仓储（SQL 逐字搬运，改命名参数）→ 服务（事务边界）。旧类保留委托方法，调用方不动。
4. **推重构**：基线全绿、并且在同一个 UTC 日内，推重构。
5. **对比轨迹**：先比 SQL 执行顺序，再比屏蔽 UUID、时间戳、摘要后的参数，只允许出现已知噪声。
6. **收尾**：修文档锚点，更新本文件。

已经踩过、必须避免的坑：

- **不要读 Spring 代理对象的字段**：带 `@Repository` 的类在 Spring 里是 CGLIB 代理，字段都是空的，只能调方法或用方法引用。只有启动完整 Spring 环境的 HTTP 测试能抓到这类问题（4A 轮出过一次，d7b4005 修复）。
- **基线和重构要在同一个 UTC 日运行**，否则 `ReconciliationRecoveryIT` 的业务日期会整体偏移。
- **已知的轨迹噪声**：3 个并发竞态测试、`ReconciliationRecoveryIT` 按随机 UUID 排序的报表数组、`persistentAccountLimit…` 里两个操作到期时间相同、先认领哪个是随机的。
- **提交身份**：本仓库用 gmail 身份提交，否则不算进 GitHub 贡献图。

## 4. 阶段 A：快速修复（预计半天，一次提交）

- [ ] 修 `Pr18FailureIdentityTest`：断言改成以 `System.lineSeparator()` 开头，或者先确认 `Pr18FailureIdentity` 本身能正确处理 `\r\n`。
- [ ] 把 `trace_compare.py` 和锚点重映射脚本放进 `scripts/refactor/`，补上用法说明。
- [ ] 加 `.git-blame-ignore-revs`，列出全量格式化和 SQL 文本块那几个提交（10-16 改写作者后的新哈希）。
- [ ] 删除本地备份分支 `backup/before-email-rewrite`（需要你确认贡献图已经正常）。

## 5. 阶段 B：剩余模块分层迁移（预计 20 轮左右）

顺序按"和已完成部分的耦合度"和"风险"排：先收尾和 payment 紧耦合的模块，资金核心（ledger）放在有充分经验之后，最大的 ops 放最后，并和拆大类一起做。

| 顺序 | 模块 | 预计轮数 | 要点 |
|---|---|---|---|
| B1 | payment 收尾：`ReconciliationSourceRecovery`、`RecoveryTaskOperations` | 2 | 夹具已有追踪；仓储并入 `PaymentRepositories` |
| B2 | dispute | 2 | 和退款共享额度控制锁；`DisputeLifecycleIT` 已经录轨迹 |
| B3 | revenueshare | 1 | 被支付、退款、争议共同调用，改完 payment 系列才稳 |
| B4 | payout | 2 | 和 bank 模块相邻，可复用 `PayoutBankFixture` 的追踪 |
| B5 | financialingestion | 2 | `FinancialSourceLocks` 目前是带 JdbcTemplate 的静态方法，要改成仓储 |
| B6 | ledger | 1 | 资金核心，改动最保守：只搬 SQL，不动记账规则 |
| B7 | webhook | 2 | `WebhookProcessor` 通过别的类的 JdbcTemplate 字段执行 SQL，要先理清 |
| B8 | notification + identity + observability | 2 | 都是小类；`SimulatedMerchantServer` 只被测试使用，直接移到测试目录即可移出白名单 |
| B9 | reconciliation | 3 | `ReconciliationChecks` 952 行，按检查项拆分 |
| B10 | ops | 3 | 和阶段 C 的拆分一起做 |

每个模块做完的验收标准：

- [ ] 该模块的类全部移出 `NOT_YET_MIGRATED`
- [ ] 新增规则类都有不依赖数据库的单元测试
- [ ] SQL 轨迹对比只有已知噪声
- [ ] 文档锚点和链接检查通过

全部做完后：

- [ ] 删除 `NOT_YET_MIGRATED` 和对应的"只减不增"测试，规则对所有生产代码生效。

## 6. 阶段 C：类型化与拆分（和阶段 B 同步进行）

- [ ] 迁移每个模块时，把它的 `Map` 行换成 record、状态字符串换成枚举（目标：737 处 `row.get` 和 107 处字符串比较降到 0）。
- [ ] `PaymentRepository.Claim` 的 `operation` / `payment` 改成类型化记录，执行器和恢复协调器不再按列名读取。
- [ ] 拆分 `OpsQueryService`（按查询对象：支付、退款、争议、对账、出款）和 `OpsCommandService`（按命令类型），单个类控制在 400 行以内。
- [ ] 拆分 `ReconciliationChecks`（按检查项）。

## 7. 阶段 D：命名清理（可选，放在最后）

- [ ] 31 个 `Pr18*` / `Pr19*` / `Pr20*` 测试类改成描述性名字，同步改 `scripts/ci/run_pr18.py` 等脚本和 `docs/evidence` 里的引用。历史证据文件只改引用，不改内容。
- [ ] `PaymentRepository` / `RefundRepository` 改名为门面类的名字（比如 `Payments` / `Refunds`）：前者 123 处引用、分布在 41 个文件，后者 64 处引用，可以用 IDE 重命名一次完成。
- [ ] 数据库函数名（`pr12_ceiling_guard` 等）和 `lease_owner='pr07'`：**建议不改**。已发布的迁移不能修改，改名要新增迁移并重建触发器，收益低、风险高；改为在代码和文档里补注释说明它们的含义。

## 8. 需要你决定的事项

1. 阶段 B 的模块顺序是否按上表？如果你更想先做某个模块（比如简历里重点写的对账），可以调到前面。
2. 阶段 D 的测试类改名和门面类改名做不做？
3. 数据库函数名是否同意"不改，只补注释"？
4. 本地备份分支是否可以删除？

## 9. 节奏与投入

- 每一轮大约 1～2 小时：两次 CI，各约 15 分钟，加上编写和核对。
- 阶段 A 半天；阶段 B 约 20 轮；阶段 C 大部分随 B 完成，额外约 3 轮；阶段 D 约 2 轮。
- 每轮都直接提交到 main，不走 PR；任何一轮 CI 失败，先修复再继续，不叠加新改动。
