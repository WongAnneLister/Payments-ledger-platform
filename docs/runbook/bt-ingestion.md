# PR10：Balance Transaction 导入与资金入账

本模块通过内部服务和显式启用的 Worker 导入提供方资金事实，没有公共任意资金导入 API。运营命令由 PR16 接入。依赖 [AccountScope](../contracts/account-scope.md)、[不可变账本](ledger-core.md)、[锁序](../adr/0007-lock-order-and-isolation.md)和[期初口径](../adr/0008-opening-balance-rebuild.md)。

## 三种进度不要混淆

1. **业务付款成功**：权威 Charge 证据确认 capture；缺 BT 时 `financialStatus=PENDING_EVIDENCE`。
2. **窗口抓取完成**：固定 created 区间的所有页面已落库，最终游标已经提交。它表示此次枚举完成，可能仍有待依赖、隔离或冲突的流水。
3. **资金入账完成**：BT 已通过归属、币种、符号和依赖校验，并由 `LedgerPostingService` 同事务写入财务事件、凭证、分录和投影。支付财务状态按实际已记账的 capture 覆盖计算。

窗口抓取完成不证明 Stripe 源数据完整。相同 BT 一份用于求和、另一份用于生成账本，只能核对本地映射；独立期末证据和 L3 完整性对账在 PR15。

## 资金规则

BT 身份为组织 + Provider 账户 + livemode + BT ID，**不含币种**。金额、费用、净额、币种或来源变化进入冲突记录，原始事实和原 Journal 保留。抓取时间、来源摘要和证据版本用于追踪每次观察；`pending→available` 更新可用性，绝不再次增加 PSP 总余额。

手算 fixture：收款 10000、实际费用 300、净额 9700，借 PSP_FUNDS 9700、借 PSP_FEE_EXPENSE 300、贷 SALES_REVENUE 10000。退款本金 4000 且实际费用为 0 时，借 SALES_RETURNS 4000、贷 PSP_FUNDS 4000；有证据支持的费用退回独立按实际符号处理，不能按退款比例猜测。

Charge 需要已有同范围成功 capture 和一致的 BT 关联。只有提供方 source ID 而没有可信本地业务关联时保留待证据，不虚构订单。退款映射已预留接线，但生产退款依赖由 PR11 提供；争议和 payout 分别等待 PR13/14 的本金及业务解释。

已验证本账户 PAYMENTS 域、净金额可验证但业务类型未知的流水：正净额借 PSP / 贷 SUSPENSE，负净额反向，保留 `UNKNOWN_SOURCE` 待处理项。Suspense 不是业务归属完成。FX、其他余额域、未启用币种和证据冲突不能进入 payments 余额。零额事实保留观察，不制造空凭证。

## 运行与恢复

提供方调用发生在数据库事务外。固定页面的观察与下一游标在一个短事务中提交；分类入账可由持久记录重放。网络失败保留已提交游标；最后一页完成前不推进窗口完成水位。不同运行保留各自固定范围；再次 backfill 同一区间会创建新运行，以发现晚到事实。并发处理使用数据库行锁及版本校验，同一 BT 重放只返回原凭证。

默认回看最近七个已结束的业务日期；时区用于计算每日 UTC 左闭右开边界，DST 的一天可以是 23 或 25 小时。固定窗口不能随着分页进行改变结束时间。历史 backfill 与 pending BT 按 ID 继续跟踪分别记录，晚到事实靠重叠扫描发现。

导入前，使用迁移/受控配置角色建立账本科目和明确的期初政策。运行角色不能配置科目、直接改投影或修改已保存证据。已有账户必须有核对过的期初 cutoff；cutoff 时刻及之前的历史 BT 不得再次计入。禁止把既有账户默认当作零期初，也禁止用 OPENING_EQUITY 自动补平差额。

## 可用性与余额核对

只读视图 `platform.psp_availability_projection` 按组织、环境、Provider 账户、模式和币种展示 PAYMENTS 域：

- `pending_net_minor`、`available_net_minor`：已入账 BT 的两种可用性金额。
- `known_bt_net_minor`：上述已入账 BT 的净额合计。
- `ledger_balance_minor`：唯一过账服务维护的 PSP 投影总额。
- `unclassified_or_opening_minor`：账本总额与已知 BT 合计的差值；期初或其他未分配可用性的金额留在这里，不猜测其 pending/available 状态。

pending→available 只移动视图中的两个子项，`known_bt_net_minor` 和账本总额保持不变。视图不写余额，运行角色只有 SELECT 权限。这是本地可用性投影，不替代同截止点的独立 Stripe 余额证明。

## 处理异常

- 缺少可信 Charge/Refund 依赖：补齐业务证据后重放原 BT，保持同一财务键。
- 缺少科目或期初政策：先由受控配置补齐，不给运行角色临时放开直接写余额权限。
- 不可变事实冲突：保留两份证据并调查；已过账凭证不能改写，修正应追加经批准的凭证。
- 未知类型：保留 Suspense 及待分类记录；后续分类不再次增加 PSP。
- 抓取失败或页链错误：检查原窗口的失败记录、固定范围、证据版本和最后已提交游标。固定 API 版本无法继续的窗口保持 INCOMPLETE 供调查，其他窗口仍能轮转推进；需要使用当前证据版本重扫时创建新的 backfill 运行。不能手工把未完成窗口改成 COMPLETE。

## Worker 配置

在 `worker` profile 下设置 `FINANCIAL_INGESTION_ENABLED=true`，并通过以下受控配置绑定唯一账户。API 角色即使误设开关也不启动导入调度。

| 配置 | 含义 |
|---|---|
| `platform.financial-ingestion.organization-id` | 本地组织 UUID |
| `platform.financial-ingestion.environment-id` | 本地环境 UUID |
| `platform.financial-ingestion.provider-account-id` | 本地 Provider 账户 UUID |
| `platform.financial-ingestion.poll-ms` | 调度间隔，默认五分钟 |

Provider 适配器仍使用原有 Fake/Stripe 配置；导入器没有另一套凭据。启用 Worker 时若无 Provider 端口或范围 UUID 无效，启动失败。缺省开关为关闭。资金导入使用独立的 `financialIngestionScheduler`；支付恢复、Webhook 和通知继续使用默认 `taskScheduler`。每轮确保当日回看运行存在，并按上次尝试时间轮转最多 5 个未完成窗口，每窗口最多 5 页；另刷新最多 25 条 pending 可用性和重放 25 条待入账记录。同一业务日的日常扫描不重复创建。历史重跑通过内部 backfill 方法创建新运行。

受控配置角色为真正从空账户启动的 fixture 注册零期初（UUID 值通过 psql 变量提供）：

```sql
INSERT INTO platform.ingestion_policy
  (organization_id, environment_id, provider_account_id, livemode, opening_mode, business_zone)
VALUES (:'organization_id'::uuid, :'environment_id'::uuid,
        :'provider_account_id'::uuid, false, 'ZERO', 'UTC');
```

已有账户使用 `opening_mode='VERIFIED_CUTOFF'`，填写 `cutoff_at`、核对来源 `evidence_ref` 和 SHA-256 `evidence_digest`。cutoff 为快照已覆盖的最后时点，仅其后的 BT 可入账。此配置仅限制历史导入范围，不代替实际期初 Journal 或经核对的外部快照。科目按[账本手册](ledger-core.md)预建：PSP_FUNDS、PSP_FEE_EXPENSE、SUSPENSE 为同 Provider 的 PAYMENTS 域；SALES_REVENUE、SALES_RETURNS 为同组织/环境/币种的 INTERNAL 科目。

## 内部调用

`FinancialImportService.createWindow(scope, from, until, purpose)` 固定范围并返回运行 ID；`purpose` 为 `BACKFILL` 或 `OVERLAP`。`runWindow(windowId, maxPages)` 有明确页数预算，`importPage(windowId)` 可逐页推进。恢复时继续使用原运行 ID，不替换原窗口范围。

页面抓取后由 `FinancialReplayService.replay(scope, btId)` 校验并入账；`FinancialImportService.refreshPending(scope, btId)` 通过提供方按 ID 读取最新可用性。以上为可信内部调用，不接受来自业务 API 的任意 scope。`RefundEvidencePort` 是 PR11 的接线边界，默认实现不提供退款业务证明。

## 本地验证

```bash
export JAVA_HOME="$HOME/.jdks/amazon-corretto-21.jdk/Contents/Home"
./mvnw -B clean verify
python3 scripts/ci/check_openapi.py
python3 scripts/ci/check_doc_links.py
git diff --check
```

`PaymentFinancialIngestionIT` 从支付服务、真实 Fake Provider 和已验签 Webhook 持久边界进入，检查延迟 BT、乱序、并发以及财务状态更新失败的原子回滚。Webhook 签名的 HTTP 验证由 `StripeWebhookHttpIT` 单独覆盖。测试隔离数据库使用 Testcontainers PostgreSQL，应用连接为 `payments_app`，配置和故障注入使用独立管理连接。

[PR10 验证记录](../evidence/pr10/README.md)区分 Fake/协议 fixture、实际数据库与真实 Stripe 账户证据。
