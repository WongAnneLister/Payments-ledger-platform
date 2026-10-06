# 客户结果页与运营观测（PR17）

API 进程提供三个同源入口：`/payment-test.html` 创建测试订单，`/payment-result.html` 查询原支付，`/ops-console.html` 查看运营资源和执行已授权动作。Worker 不提供页面或业务 API。部署方式与 API 相同，没有前端打包器或额外运行服务。

## 使用页面

客户结果页需要原 `pay_…` 和该订单所属的 `app_…` 项目凭据。凭据只保存在当前页面内存；从收银台返回时重新输入。URL 中的 success/cancel 标志、通知以及历史创建响应均不作为付款证据。UNKNOWN/CONFLICT 应继续查询原资源；刷新失败会移除旧确认结果。只有 KNOWN、SUCCEEDED、完整捕获金额和 POSTED 同时成立且证据未截断时显示绿色付款成功。业务已成功但 PENDING_EVIDENCE 时显示黄色“付款已确认，账务待齐”。

运营控制台输入 `ops_…`，通过 `/ops/identity` 验证受信任身份后再查询。不要把 Stripe 私钥填到页面。凭据、动作正文都不写入浏览器持久存储；切换凭据清除旧身份数据并取消旧请求。带 `view`、`id` 的同源链接仅提供导航，仍需验证身份和查询。

| 视图 | 原有查询 API |
|---|---|
| 支付详情 | `GET /ops/payments/{id}` |
| 退款详情 | `GET /ops/refunds/{id}` |
| 争议详情 | `GET /ops/disputes/{id}` |
| Payout 详情 | `GET /ops/payouts/{id}` |
| 分润详情 | `GET /ops/revenue-shares/{id}` |
| Journal 列表 / 分录 | `GET /ops/ledger/journals`、`GET /ops/ledger/journals/{id}` |
| 余额 / 时点核对 | `GET /ops/balances` |
| 对账运行 | `GET /ops/reconciliation-runs/{id}` |
| 差错 / 恢复任务 | `GET /ops/exceptions`、`GET /ops/tasks` |
| 调整草稿 | `GET /ops/adjustments/{id}` |
| 观测与告警 | `GET /ops/observability`（PR17） |

金额保留整数最小单位和币种，超过 JavaScript 安全整数的值也不四舍五入。小数残差保留原十进制文本。账本、BT、Provider、银行证据分别呈现；银行 fixture 明示 `SIMULATED_BANK_EVIDENCE`。空记录、截断记录、未提供来源和 INCOMPLETE 均不能当作资金核对通过。

筛选输入是 JSON 对象，支持字段随视图校验。分页使用服务端游标；不将游标或组织声明当作授权。详情页关联 ID 可跳转到对应查询。页面通过文字节点显示外部内容，不执行其中的 HTML。

## 人工动作与复核

OPS_VIEWER 只读；OPS_OPERATOR/OPS_ADMIN 可发起原有五类动作；OPS_REVIEWER 只读并可审批调整，须先查询草稿并核对发起人与拟议分录。PARTNER_VIEWER 只有本人分润导航，服务端限制本人明细。作者切换角色也不能审批自己的调整。

| 动作 | 原有命令 API |
|---|---|
| 创建对账运行 | `POST /ops/reconciliation-runs` |
| 导入模拟银行 fixture | `POST /ops/bank-statements/import` |
| 差错动作 | `POST /ops/exceptions/{id}/actions` |
| 重试原恢复任务 | `POST /ops/tasks/{id}/retry` |
| 创建调整草稿 | `POST /ops/adjustments` |
| 独立审批 / 拒绝 | `POST /ops/adjustments/{id}/approve` |

动作请求含 reason，组织/环境由受信任身份决定。响应丢失时正文和原幂等键被冻结，使用“按原请求重试”，不要创建替代动作。明确拒绝后可释放草稿并修正；已接受的响应显示为历史记录，随后 GET 原资源当前状态。切换视图不会改变原请求目标。页面权限提示不能替代服务端授权。

## 指标与告警口径

`GET /ops/observability` 在一个只读、可重复读事务中采样，只包含当前受信任组织/环境下的 ACTIVE 测试账户和已启用币种。PARTNER_VIEWER 无权访问。响应提供 `observedAt`、各项 `sourceAt`、精确十进制字符串和按账户/币种区分的对账残差。查询失败返回 503；不会伪装成零积压。

| 指标 | 持久数据口径 | 页面告警阈值（含边界） |
|---|---|---|
| `unknown_payment_count` | resolution 为 UNKNOWN 的支付数；CONFLICT 不混入 | ≥1，WARNING |
| `unknown_payment_oldest_age` | 最早 UNKNOWN 的 unresolved_since，缺失时 created_at | ≥900 秒，WARNING |
| `refund_reserved_age` | LOCAL 退款处于 RESERVED/SUBMITTED/PENDING/REQUIRES_ACTION 的最早时间 | ≥3600 秒，WARNING |
| `webhook_lag` | 符合账户范围、至少有一个启用币种的非 DONE Inbox，取最早 received_at；Inbox 无币种列，按账户计一次 | ≥300 秒，WARNING |
| `outbox_oldest_age` | 非 PUBLISHED Outbox 的最早 occurred_at | ≥600 秒，WARNING |
| `ledger_pending_evidence` | PENDING_EVIDENCE/PARTIALLY_POSTED 支付行，加 PENDING_EVIDENCE 的 PAYMENTS BT 行 | ≥1，WARNING |
| `unclassified_bt_count` | 已持久化 UNKNOWN_SOURCE/UNKNOWN_SOURCE_ZERO_MOVEMENT 分类，包括暂记 SUSPENSE 和零流水 | ≥1，WARNING |
| `trial_balance_failures` | 各账户/币种最新对账运行中，第 1 项检查 FAIL 的数量 | ≥1，CRITICAL |
| `reconciliation_residual` | 最新第 5 项 PSP 独立期末核对残差；仅单一账户/币种且检查完整时提供标量 | 非零，CRITICAL |
| `payout_unmatched_age` | 银行匹配 UNMATCHED/PARTIAL/CONFLICT 的最早 payout created_at | ≥86400 秒，WARNING |
| `share_cap_violations` | 各账户/币种最新运行中，第 3 项分润检查 FAIL 的数量 | ≥1，CRITICAL |

年龄以 observedAt 计算精确秒数，未来时间钳制为零。计数查询成功且无记录时为 AVAILABLE/"0"；无年龄来源、缺对账检查或 INCOMPLETE 时为 NO_DATA/null。最新报告按 created_at、id 选取，不回退到较早 PASS。第 1 项还检查事件覆盖，第 3 项还检查政策和凭证覆盖：上述失败计数不是坏分录数或单独超额笔数。pending_evidence 计的是待处理工作行，支付和其 BT 可各计一次。

残差行保留 runId、币种、账户、outcome 和 dataCompleteness。缺第 5 项时 residualMinor 为 null、checkPresent 为 false；多维度标量不相加，显示 NO_DATA 并提示逐项查看。标量还要求运行 COMPLETE、检查存在且非 INCOMPLETE，并且检查完整性中没有 unresolved、缺页或其它待处理输入。确定违规可让检查显示 FAIL，同时输入仍不完整：保留逐项失败和局部残差，标量显示 NO_DATA，不能用局部零值证明已核对。缺检查或 INCOMPLETE 会产生 WARNING，不能视为 PASS。告警只观察并提示，不执行资金调整、重试或外部通知。

内部 Micrometer 注册固定 11 个 `payments.observability.<指标名>` gauge，无组织、订单等高基数标签，直接读取持久数据，与最近一次 HTTP 调用无关。它覆盖全部 ACTIVE 测试范围；每个 gauge 独立采样，多项读数不保证来自同一快照。Micrometer 使用 double，监控大值可能近似；精确值和阈值判定以 API 的十进制字符串为准。缺数据/查询失败为 NaN，多币种残差不合计。公开 Actuator 仍只提供 health。

## 关联日志

成功的运营查询、命令及观测产生 `ops.read`、`ops.command`、`ops.observability` JSON 事件。只输出实际已知、格式受限的 requestId、businessOrderId、paymentId、operationId、providerRequestId、balanceTransactionId、financialEventId、journalId、runId；未获得的关联不猜测。账本和观察各行可输出独立关联事件，每个投影区段最多 100 行。

使用 Spring ECS 结构化控制台输出时，这些字段同时进入 MDC。日志不记录凭据、client secret、卡号、银行 token、reason、原始请求/响应正文或任意 metadata。可疑来路的 requestId 会重新生成；已知本地 UUID/类型 ID 的验证与外部 requestId 分开。原有观察保留的 providerRequestId 也经过过滤才投影，原始事实不会递归进入日志。

## 运行验证

前端回归无第三方依赖：`node --test scripts/tests/pr17-*.test.mjs`（CI 使用 Node22）。Java21 的 `./mvnw -B clean verify` 验证真实 HTTP/PostgreSQL 权限和资金语义。

实际浏览器冒烟工具为 `scripts/demo/pr17-browser-smoke.cjs`。先构建 jar，配置 `PLAYWRIGHT_MODULE` 指向已安装 Playwright，必要时配置 `CHROMIUM_EXECUTABLE`、`PR17_JAVA`。脚本创建全新 PostgreSQL16 容器，在 loopback 上启动 api/local/test 和 Fake Provider，随机生成内存凭据，验证页面后停止 API、删除容器和临时文件。不加载 `.env`，不调用真实 Stripe 或银行。输出目录可用 `PR17_SMOKE_OUTPUT` 指定，默认 `docs/evidence/pr17/browser`。无 API 密钥出现在命令参数、截图和结果 JSON 中。

截图来自该隔离环境。浏览器仅在失败刷新和丢失命令响应用例拦截网络以模拟传输错误；其它数据均来自真实 API 和 PostgreSQL。真实账户、银行入账和线上告警交付不属于该冒烟验证。
