# PR15 对账与 L3 回补

PR15 复用 PR10 的分页导入、PR07 的 `recovery_task` 租约、支付规范化合并、FinancialReplayService 和 PR03 的独立重建。不会为了消除残差写调整凭证。运营 HTTP API 属于 PR16；当前入口是内部服务和 opt-in worker。

## 启用与窗口

使用 worker profile，并配置 `platform.financial-ingestion.enabled=true`、`platform.reconciliation.enabled=true`、已有的 financial-ingestion organization/environment/provider-account UUID。范围必须是 ACTIVE 测试账户和已启用币种。`platform.reconciliation.currency` 默认 USD，`platform.reconciliation.business-zone` 默认 UTC。

夜间 cron 默认 `0 10 2 * * *`，按业务时区生成前 1～7 个业务日的新报告版本。每个日窗由当地相邻午夜转换成 UTC，DST 日可以是 23 或 25 小时。`createBackfill(scope,currency,from,until,zone)` 支持明确的历史范围。重跑创建新 run；稳定 BT 和 Journal 仍只记一次。

轮询默认 5000ms，每次最多处理 5 个 run，每个 run 最多导入 5 页、领取 5 个来源任务、回放 25 个 BT；持久 replay cursor 跨批次继续，初次回放遍历完成前不冻结。按 cursor 更新时间轮转，未完成旧窗口不会永久挡住新窗口。分页链、API 版本、实际 import_page 及 BT 事实都参与完整性判断；单独的 COMPLETE 字段不能证明已枚举全量。

## 回补与履约

Charge → PaymentIntent → 不可变服务端 order_provider_binding 才能关联既有订单。核实 scope、币种、全部成功 capture、BT/Charge/PI 身份和金额、API 版本及当前订单资格。metadata 或同金额只提供线索。绑定由服务端原生创建命令和确认身份产生；独立测试绑定由迁移角色写入，运行角色不能创建绑定。

没有可信订单时只追加未分配观察、异常和 ESCALATED 任务，不创建订单、支付或履约。暂时读不到来源则 WAITING，使用同一任务再次领取。提供方读取始终在本地事务外；本地按订单、支付、任务锁序验证 token/version/未过期租约，原子保存关联及通知，之后使用原有账务重放入口。

新增支付保留 `origin=RECONCILIATION`、runId、source BT、摘要和不可变 provenance。只有当前仍合格的订单才能通过历史通知履约检查。此类 GET 返回已确认的本地事实；后续来源恢复由 L3 任务负责，不伪造 CREATE_CHECKOUT 操作。

## 七项检查与三层证据

| 检查 | 内容 |
|---|---|
| 1 | 每张凭证封存与借贷平衡、已接受事件和 Journal 双向覆盖 |
| 2 | 退款 C/R/H 重算、冲突、老化预占与租约告警 |
| 3 | 下单分润快照、累计上限、调整历史与实际 Journal |
| 4 | BT 全分页、稳定事实与本地资源/事件覆盖，版本化费率预期 |
| 5 | 期初 + 完整 BT、截止点账本、独立提供方期末三方核对 |
| 6 | Payout 本金、在途、银行本金、返还及分列费用的实际历史与分录 |
| 7 | PR03 分录独立重建与投影比较、科目和提供方来源隔离 |

每项结果是 PASS、FAIL 或 INCOMPLETE，固定数据库快照、规则版本、输入事实/水位/版本、residual、原因和负责人。已确定违规即 FAIL；即使其余证据缺失也不降格成 INCOMPLETE。run 的 COMPLETE 表示证据完整，不表示所有结果 PASS。

独立期末只能来自同 scope/币种/域/窗口的期间报告，或有明确静止证明的受控快照。当前余额不能冒充昨日余额，BT 与同一 BT 生成的账本相等也不能独立证明源数据无漏项。报告请求与查询在事务外，pending 报告保存 reportId 并继续 fetch；超过一天仍未完成则冻结 INCOMPLETE。已知 pending 报告遇临时失败时保留原 reportId，在期限内继续 fetch；永久不可用、明确终止或范围不匹配才冻结 INCOMPLETE。账户级分页保留全部 ID，财务检查只评价本 run 的币种与 PAYMENTS 域。

手续费模型按范围、业务类型、有效区间与版本选择，以整数 bps/固定费用/明确舍入产生预期，并固定 FOLLOW_PRINCIPAL、EXPENSE 或 REBATE 的费用方向，出款费用与返还费用不照搬本金正负号。缺失或歧义为 UNMODELED_FEE；预期不修改实际 BT 或银行费用。银行依据仍明确标识 SIMULATED_BANK_EVIDENCE；没有完整银行范围不能误报短款。

## 冻结、恢复与证据

`finish(run,evidence)` 在一个 REPEATABLE READ 快照内写入恰好七项结果并冻结报告及其输入。冻结的 INCOMPLETE 报告不能修改；任务可以继续恢复并追加来源观察，后续夜间新 run 捕获新事实。运维应追踪原 run、sourceObservation、任务原因及新报告，不把旧结果改成 PASS。

[固定独立示例](../fixtures/reconciliation-independent-v1.json)记录缺失外部退款造成的 100 minor 残差；[PR15 验证记录](../evidence/pr15/README.md)记录实际测试与来源。当前 Stripe 报告适配器明确返回 historical_report_contract_unverified，因此真实历史报告路径保持 INCOMPLETE。真实账户历史报告和完整银行核对未运行，不能用 fixture 宣称真实账户守恒。
