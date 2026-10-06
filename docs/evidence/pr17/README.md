# PR17 页面与观测证据

基线：`fc09cb234b48f145686d5b24ef796fca931c1379`；开发分支：`codex/pr17-ui`。本期交付客户结果页、运营控制台、作用域观测 API、11 项持久指标和允许列表关联日志。未改迁移、资金规则或恢复租约。

## 页面/API 覆盖

| 页面/能力 | 真实 API | 验证来源 |
|---|---|---|
| 客户结果 / UNKNOWN | `/v1/payments/{id}`；原测试入口 `/v1/payments` | Node 实际页面模块、PageHttpIT、PaymentHttpIT、实际浏览器 |
| 支付、退款、争议、payout、分润详情 | `/ops/payments/{id}`、`refunds/{id}`、`disputes/{id}`、`payouts/{id}`、`revenue-shares/{id}` | 13 视图路由测试、OpsApiIT 权限/响应/范围/证据 |
| 账本列表 / 分录、余额、对账 | `/ops/ledger/journals`、`journals/{id}`、`balances`、`reconciliation-runs/{id}` | 精确金额/XSS/完整性 Node 测试、OpsApiIT |
| 差错、任务、调整 | `/ops/exceptions`、`tasks`、`adjustments/{id}` | Node 路由/角色测试、OpsApiIT 独立审批/审计 |
| 观测面板 | `/ops/observability` | ObservabilityIT 真实 PostgreSQL、固定 gauge 单元测试、浏览器 |
| 六类人工动作 | 对账、银行 fixture 导入、差错动作、任务重试、调整草稿、审批 | Node 路由/身份/冻结键与正文、OpsApiIT；浏览器真实对账响应丢失/重放 |

以上路由由同源 API 客户端访问。页面不访问数据库；SQL 仅用于测试工具新建的隔离数据库 fixture。角色和组织/环境均由服务端身份决定。所有 12 个 PR16 查询、6 个 PR16 命令与新增观测查询均有覆盖；不意味着每个页面都已进行人工逐项截图验收。

## 验收映射

- **AC-V1-32：** 页面、API、权限、UNKNOWN、证据完整性、时间线、指标、告警、日志脱敏和来源；由本目录记录和[运行手册](../../runbook/pages-observability.md)对应。
- **AC-V1-15：** 本期验证重复通知只重新查询原支付一次、历史命令重放随后读取当前资源、旧/失败刷新不保留旧成功。既有商户持久化履约语义由 PR09/PR15 Java 回归继续覆盖；本期没有新增或重新声称真实商户通知交付。

## 实际浏览器、样本与截图

[最终浏览器结果](browser/result.json)：实际 Chromium 147.0.7727.15 / Playwright 1.62.1，15/15 检查 PASS，进程退出 0，8 张截图。父执行者查看了桌面、客户结果和手机指标布局，未发现溢出、错误绿色或凭据明文。结果记录被测 jar 的 SHA256。

- [客户 UNKNOWN](browser/customer-unknown.png)、[业务已确认但账务待齐](browser/customer-pending-evidence.png)。
- [真实运营支付轨迹](browser/ops-payment-trace.png)、[原请求重放后的当前 INCOMPLETE 对账](browser/ops-reconciliation-replay.png)。
- [观测面板视口](browser/ops-observability-viewport.png)、[完整观测](browser/ops-observability.png)、[手机指标视口](browser/ops-mobile-metrics-viewport.png)、[完整手机布局](browser/ops-mobile.png)。
- [精确指标与告警样本](browser/observability-sample.json)、[实际 ECS 允许列表关联样本](browser/correlation-samples.json)。

浏览器检查包括 URL success 不能产生确认/自动未认证 GET、原支付 UNKNOWN、重复通知一次查询、同 ID 延迟/失败刷新清除旧确认、身份切换、API 恶意文字不执行、伙伴权限拒绝、同身份复验保留请求正文、响应丢失后使用完全相同正文/键重试并且只创建一个运行、11 张真实指标卡、390px 无横向溢出与键盘焦点、无未捕获 JS 错误、ECS 日志没有生成的凭据。

工具修复过程也有记录：容器临时 socket 服务先于数据库创建就绪，正式 TCP 就绪才可运行 fixture，[实际探针](browser-postgres-readiness-probe.txt)复现该区别。结果页初始化已显示未知占位，工具原先绑定“尚未查询”字样；最终断言验证未确认、无证据、无未认证 GET 的安全语义。两项失败记录保留，不计入 PASS。

## RED 与审查记录

[基线](baseline.json)、[UI RED](ui-red.json)、[UI GREEN](ui-green.json)记录命令和预期断言；[UI 规格审查](ui-spec-review.md)和[质量审查](ui-quality-review.md)均在修复后通过。实际页面生命周期测试曾发现重放遗漏当前 GET、重验身份丢失动作目标、等待动作矛盾和同 ID 刷新保留旧确认，均保存 RED/GREEN。

[观测 RED](observability-red.json)记录不存在端点/gauge/日志的失败，以及真实关联 ID 被过度过滤、小数残差 500、关联字段缺失的回归。`observability-first-green-failed.txt` 是失败过程记录，不是完成证明。`browser-missing-observability-red.txt` 明确记录后端尚缺时实际浏览器失败，避免将占位卡片当成成功数据。

[观测规格审查](observability-spec-review.md)发现并修复 FAIL 与输入完整性独立的边界；[观测质量审查](observability-quality-review.md)发现并修复嵌入 key 前缀的脱敏绕过。两者最终 PASS/Approved；最终聚焦回归见[观测结果](observability-final-green.json)。

## 构建与输入追溯

[输入清单](inputs-sha256.json)固定 309 个文件的 SHA256，包括源码、资源、测试、浏览器工具、CI、Maven Wrapper、构建与 OpenAPI；不读取真实 .env。最终 Node22 回归 35/35 PASS，见[node 结果](node22-final-green.txt)。Java21 `caffeinate -i ./mvnw -B clean verify` 已退出 0，BUILD SUCCESS，耗时 10:20。207 项单元/上下文测试通过；459 项集成测试中 456 项通过、3 项跳过，合计 **663 PASS / 3 SKIP / 0 失败 / 0 错误**。见[结构化结果](final-verification.json)与[完整构建日志](java21-clean-verify.txt)。

跳过项仅为 `PaymentRealContractIT`、`PayoutRealReadIT`、`StripeRealContractIT`：对应 `PR06_STRIPE_CONTRACT`、`PAYOUT_REAL_READ_TESTS`、`STRIPE_CONTRACT_TESTS` 未启用，本期没有重新运行真实账户契约。日志包含测试结束后的 fork JVM 退出等待 30 秒诊断；Maven 退出码为 0，所有 XML 报告失败与错误均为 0。归档文本日志已脱敏，并清理行尾空白和末尾空行；测试结果内容未改动。

构建后已验证 309 项输入无漂移；提交时逐项核对暂存内容与被测输入，检查记录见[交付检查](delivery-checks.json)。[最终整体审查](final-review.md)记录交付结论。

## 证据边界

浏览器使用独立 Chromium、Fake Provider 和全新 PostgreSQL16，经真实 HTTP 访问。本地 UNKNOWN/已确认待证据状态由隔离 fixture 设置；传输中断由浏览器路由明确注入。银行卡和 Stripe 真实账户能力、真实银行到账、线上告警外部交付为 **NOT_RUN**；没有读取项目 .env 或执行新真实资金操作。银行 fixture 继续标记 **SIMULATED_BANK_EVIDENCE**。

内部 gauge 使用 double 监控近似值；API 和阈值使用精确十进制。指标失败检查数量与数据范围详见运行手册，不能当作单独坏分录数量。告警不改变 PASS/INCOMPLETE 或资金状态。
