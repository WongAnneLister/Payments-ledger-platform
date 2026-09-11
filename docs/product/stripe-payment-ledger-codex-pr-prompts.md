# 从零构建 Stripe 支付与账本：逐 PR 执行提示词

版本：1.1 · 日期：2026-09-10 · 状态：规划文档，未执行任何 PR

相关文档：[产品需求](payment-platform-prd.md) · [实施路线图](payment-platform-roadmap.md) · [验收矩阵](payment-platform-acceptance.md) · [面试材料](payment-platform-interview-questions.md)

## 使用方式

一次复制一个完整代码块到独立新工程的任务中，按 PR00–PR20 顺序实施。每个块都包含范围、依赖和结束条件，不需要另加旧版“通用前缀”。本文中的命令式内容是未来执行提示词，本次文档修订不执行它们。

新工程不使用、复制、参考或扩展 Jeepay。六份产品文档放在新工程 `docs/product/`。代码 PR 可以修改此前已创建的文件，以完成当前功能接线；实体/字段列表是设计重点，不是阻止补齐范围字段、约束、审计、版本或索引的白名单。不要为遵守文件清单而留下未接通的组件。

全局约束：Java 21、Spring Boot、PostgreSQL、Flyway、Kafka、Redis；模块化单体，API/Worker 独立运行且共享 PostgreSQL 事务域。Stripe 只在 Provider 适配器内访问，所有网络调用在数据库事务外。金额采用最小货币单位精确整数并检查溢出；UNKNOWN 不等于失败。封存 Journal 不可更新、删除或补插 Entry；余额投影只由 LedgerPostingService 在过账事务内更新。仅一个实际 Stripe 测试账户/组织/运行币种；其他 scope 用 fixture。禁止 live 凭据、真实资金、Connect、合作方实付、银行直连、二维码、门店和完整电商目录。

每个 PR 结束都要报告实际证据。适用检查未运行用 NOT RUN，失败用 FAIL，实际通过用 PASS；不适用为 N/A 并给原因。测试账户能力受阻另外标 BLOCKED，fixture-only 不能冒充真实联调。基础测试随功能交付；PR18/19 扩展验证。若发现真实缺陷，允许最小生产代码修复及回归，不能更改资金规则来迎合测试。不编造命令输出、缺陷数、性能或生产成绩。

## PR00：规范、契约与决策冻结

```text
在用户明确指定的独立新工程位置执行 PR00。项目完全从零实现，不使用、复制、参考或改造 Jeepay。只整理文档与契约，不写业务代码，不运行构建或数据库删除。先检查当前位置、已有文件及可用的 git status/diff，保留用户改动；没有仓库时如实记录。

读取 docs/product/payment-platform-prd.md、payment-platform-roadmap.md、payment-platform-acceptance.md 和本提示词文件。依赖：无。列出计划后直接完成当前文档范围。

交付：
- docs/spec/requirements-map.md：需求、I-01～07、F-01～08、AC-V1-01～38 与 PR00～PR20 双向映射，旧 AC-xx 命名空间废止。
- docs/contracts/openapi.yaml：首批接口完整请求、响应、认证、安全范围、幂等、错误码和示例；不能只放路径占位。
- docs/contracts/account-scope.md：组织、应用权限、Provider账户、environment/livemode、币种、balance domain 的可信绑定、唯一键与外键规则；演示银行和分润账户的来源归属。
- docs/contracts/provider.md：Checkout create/query/expire、PI/Charge query、Refund create/query、BT/Dispute/Payout/Balance/报告取证端口、幂等窗口与错误分类。
- docs/state-machines/payment.md、refund.md、dispute.md、payout.md、provider-operation.md：允许、拒绝、幂等重复转换及证据优先级；本地状态、providerStatus、resolution 和 financialStatus 分开。
- docs/ledger/accounts.md、invariants.md、posting-examples.md：固定演示会计政策、七项不变量、提交可约束与需独立核对的部分，至少覆盖收款、退款费用/返还、分润、争议late win、payout银行重叠。
- docs/adr/：模块化单体与API/Worker、精确金额与时区、Provider未知结果、不可变账本、操作关联方式、认证边界的决策。
- docs/spec/version-and-capability-matrix.md：Java21、Spring Boot、PostgreSQL、Flyway、Kafka、Redis、stripe-java和Stripe API的候选/冻结版本、来源、待验证项、验证PR与状态；PR01/05实际验证。不虚构“最新版本”或测试账户能力。

首批完整契约包括 POST/GET payments、POST payment cancel、POST/GET refunds、Stripe webhook；列出PR16全部运营接口归属并把运营写命令的权限、审批和幂等语义写清。每个错误有 code、retryable、requestId。请求金额须以受控价格/业务订单校验；可信凭据绑定TenantContext，不信任请求里任填组织或账户。

必须决策：provider_operation使用payment_id/refund_id互斥关联（退款FK在PR11添加），或有数据库约束的关联表；同一个通用恢复模型覆盖两者。锁顺序必须覆盖支付控制→退款/分润/争议→财务事件/账户，多个账户按稳定顺序锁定；避免不同处理入口反向加锁。

账户与账本设计必须保证POSTED是最终封存状态，一次性受控写入后禁止改删和补插Entry，提交时平衡。明确I-02唯一键不能独自证明事件无遗漏；I-06本地分配上限可提交拒绝，完整外部守恒要等证据；I-07隔离约束与独立余额重建分别验证。

验收：全部AC-V1有负责人PR，PR05真实集成、PR11退款恢复/通知/BT、PR12退款分润原子接线、PR13争议阻断、PR15独立证据均无遗漏；所有相对链接可解析；手算示例守恒且符号一致；OpenAPI能解析且首批schema非空。
构建、单元测试、数据库迁移为N/A（本PR只文档），不是NOT RUN。报告实际文档/契约检查和仍待PR01/05验证项，不宣称运行验收已通过，不执行未来PR。
```

## PR01：独立工程骨架

```text
执行 PR01：从零建立Java21模块化单体与独立API/Worker角色，不使用任何旧项目代码。依赖PR00。先读取docs/product中的PRD、路线图、验收矩阵及PR00契约，检查工作区改动，列明文件、配置和验证计划后实施；保留用户已有修改。

当前新增：pom.xml、Maven Wrapper（mvnw与mvnw.cmd及所需文件）、启动类、统一错误响应/requestId/Clock、结构化日志基础；identity/payment/provider/ledger/messaging/refund/revenue_share/financial_ingestion/dispute/payout/reconciliation/ops模块包。配置API与Worker运行角色，同一代码库与PostgreSQL事务域；Worker提供受控健康检查，不暴露业务写入口。
基础设施：PostgreSQL、Redis、Kafka Compose及健康检查、Flyway baseline、Dockerfile、CI、环境变量示例、Windows与Unix启动说明。读取PR00版本表，实际验证依赖组合并记录锁定版本。仅提供GET /actuator/health；不创建payment、refund、journal等业务表，不接Stripe SDK资金调用。

事务：只初始化连接、迁移及事务管理配置，无业务资金事务。日志从本PR起屏蔽密钥、完整token与敏感请求内容。应用角色、迁移角色的权限分离方案写入运行说明。

验证：Wrapper可用；构建及最小上下文测试；Compose依赖健康；API/Worker可分别启动；health响应；在本PR明确新建、命名唯一的隔离测试库执行空库迁移与第二次启动。重建前核对host/port/database与目标库名，绝不删除已有或未核实数据库。无需以删除用户数据库作为验收。

当前验收：工程门槛，支撑全部AC-V1；不冒认后续业务验收通过。记录实际命令与结果、版本与环境条件、git diff --check；适用未运行NOT RUN，失败FAIL，不适用N/A。当前只完成骨架。
```

## PR02：身份、认证和账户范围

```text
执行 PR02：可信身份和账户范围。依赖PR01与PR00 AccountScope契约。读取docs/product的PRD/验收、认证ADR及现有代码，检查工作区后列计划并直接实施。项目为完全从零Java21/PostgreSQL工程，不引用旧系统。可以修改骨架文件完成安全接线。

当前新增或迁移：organization、application、application_credential、provider_account、environment_config、currency_config、运营身份/凭据基础；受控商品价格及分润政策fixture配置。字段补齐归属、权限、状态、版本、有效期与必要唯一/FK；不建设门店、完整商品目录或自有钱包。
实现认证过滤器、TenantContext、TenantAccessGuard、资源scope查询约束。应用凭据只存安全摘要，凭据与organization/application/provider_account固定绑定；运营身份与业务应用权限分开。不得把请求body/header任填tenant当认证。为健康检查、webhook验签入口和业务API明确不同认证方式。
当前不新增支付/退款API；提供受控的本地fixture初始化及必要只读身份信息，不暴露任意公共账户管理能力。

事务：配置写入验证同一组织/环境关系；凭据解析生成不可任意覆盖的请求上下文；后续资源查询必须显式带scope。PostgreSQL约束阻止跨组织FK，应用授权阻止读取其他应用资源。定义应用权限与资金AccountScope差别，不能把应用维度误当Stripe真实分账账户。

测试：组织A不能读写B；同组织不同应用权限边界；缺失/失效凭据与缺TenantContext被拒；伪造组织/Provider账户无效；test/live混用拒绝；USD与独立JPY零小数fixture；负数、溢出、不支持币种和非法minor_unit；本地默认拒绝live凭据。
验收ID：AC-V1-01、AC-V1-02的身份/配置部分。只一个真实测试账户，额外账户是fixture，报告明确区分。
运行构建、当前PostgreSQL集成测试、适用的隔离空库迁移和diff检查。报告实际命令、AC证据与未完成项；未运行NOT RUN，不适用N/A，不执行支付功能。
```

## PR03：不可变复式账本

```text
执行 PR03：不可变账本。依赖PR02与PR00会计/不变量/锁序契约。读取docs/product PRD和AC-V1-02、17，检查当前工作区，列出迁移/约束/事务/测试后实施。可修改已有基础代码；业务模块不得绕过唯一过账入口。

当前新增：ledger_account、financial_event、journal、journal_entry、balance_projection及过账控制/封存机制。核心约束包括scope+event_key唯一、financial_event与最终Journal一对一、金额有效、至少两行、同币种借贷平衡、组织/环境/币种一致。表字段清单不是白名单，补齐适用Provider归属、来源域、审计和索引；演示银行/分润账户不强制伪造外部账户，但来源归属明确。
实现LedgerPostingService.post、ReversalService.reverse、JournalQueryService、BalanceRebuildService；不新增公共任意入账API，不调用Provider。

事务：按PR00稳定锁顺序取得必要控制行/账户→唯一财务事件→受控一次性写Journal/Entry→校验→更新projection→提交。Journal对外只有最终POSTED；草稿停留在构建/观察层。数据库提交约束必须能拦截失衡、少于两行和跨scope，不能只靠Java校验。封存后禁止UPDATE/DELETE Journal或Entry，也禁止给旧Journal补INSERT Entry。说明受控构建与封存如何兼容，而不是先封存再任意追加行。
只有LedgerPostingService能在同一事务更新余额projection；导入器不能再次累加。冲正用关联的新financial_event与新Journal，不能改原事件或旧凭证；重复冲正命令幂等。

测试使用真实PostgreSQL：合法示例、空/单行/失衡/溢出、跨组织/币种/来源域、同键并发只一Journal、事件和凭证原子回滚、提交约束、直接SQL改删及补插被拒、重复冲正、独立扫描Entry重建与projection比对。使用适当应用权限角色验证防护，不把owner/superuser绕过能力当普通权限。
验收ID：AC-V1-17、AC-V1-02的金额/账本部分；I-01、I-02提交唯一性、I-07隔离与重建。事件全覆盖仍需后续扫描，不能以唯一键声称无漏账。
运行构建、相关单测/数据库测试、隔离空库及升级迁移、diff检查，报告实际结果。适用未运行NOT RUN、不适用N/A；不提前实现BT或分润。
```

## PR04：Provider 端口与故障模拟器

```text
执行 PR04：Provider端口和FakeStripeProvider。依赖PR00、PR01。读docs/product PRD/验收及Provider契约，检查工作区，列接口、故障脚本和测试计划后实施。完全从零，不读取旧项目代码。

端口按Checkout实际语义定义：create/query/expireCheckoutSession；queryPaymentIntent；queryCharge/attempts；create/queryRefund；list/getBalanceTransactions；list/getDisputes；list/getPayouts；读取Balance和适用报告/取证结果。不提供未经本期业务使用的泛化confirmPayment假接口。Provider对象、错误和证据模型与Stripe SDK隔离，后续真实SDK仅在adapter。
当前新增FakeStripeProvider、FailureScript、ProviderCounters、持久/可重放fixture状态及可控Clock。业务表/API不新增；模拟管理接口只能测试profile内访问。

支持：发送前失败、受理后响应丢失、短期查询为空、暂态错误、拒绝、客户认证、多Charge attempt、session过期/取消与成功竞争；Webhook丢失/重复/乱序；BT分页/晚到/状态变化；退款pending/failed与返还；争议与payout证据fixture。
Fake必须有明确幂等保留窗口，窗口届满可能按新请求处理，不能永久神奇去重。统计requestCount、acceptedCount、sessionCreatedCount、paymentEffectCount、refundEffectCount等，实际模拟资金效果计数独立于本地payment/Journals数量。Checkout创建不直接加扣款效果计数。

事务：模拟器执行与本地数据库事务无共享原子性；支持在远端效果已发生、本地尚未回写时注入中断。稳定外部ID和证据可查询；严禁让Fake根据本地业务状态自动修正远端。
测试每个脚本的可复现输出、窗口内重复与过期键产生不同风险、受理后超时仍有外部效果、状态与BT先后独立、独立效果计数。为后续AC-V1-05～10和35提供fixture，不冒认真实契约通过。
运行构建、模拟器测试、适用检查及diff检查。无schema变化的迁移检查N/A并说明；适用未运行NOT RUN。只交付端口和模拟器，不实现业务支付/退款或账务。
```
## PR05：真实 Stripe 适配与契约验证

```text
执行 PR05：真实Stripe测试适配。依赖PR02、PR04。读取docs/product PRD/验收、Provider契约和版本能力表，检查工作区后列计划并实施。仅使用用户已配置的测试凭据；不输出密钥，不用live账户，不把本地模拟称作Stripe实测。

新增StripeCheckoutAdapter、StripeRefundAdapter、StripeFinancialReadAdapter、StripeWebhookVerifier及契约fixture/测试；可拆合实现类但保持Provider端口隔离。业务API/业务表本PR不新增；必要配置和脱敏契约证据可扩展。冻结实际验证过的stripe-java、Stripe API、webhook endpoint版本和测试账户范围。
验证Checkout创建/查询/expire、托管url、PI与Charge关联、多attempt、自动capture；metadata在Checkout和payment_intent_data中显式传递并核验，不能假设跨对象自动传播。记录取消/过期适用状态和竞争结果，不能把expireSession等同于取消任何成功PI。
验证create/queryRefund、BT分页与amount/fee/net/source/status/available_on、Dispute、Payout及Balance/报告取证读取。定义对象不存在、暂态查询空、网络超时、确定拒绝、认证动作与未知结果的归一化分类。以官方契约与当前冻结版本为依据，不臆造字段或可用能力。

事务：Provider调用不持有本地业务锁；适配器返回事实和证据，不能自己更改payment状态或直接入账。记录requestId、对象ID、账户scope和脱敏摘要，避免secret/raw卡数据进入日志。签名测试使用原始字节与专用测试secret；保留真实接收fixture与本地签名fixture来源标签。
测试：少量真实测试账户契约，Checkout→PI→Charge→BT可追溯；拒绝/认证/多attempt；可用状态下过期和取消竞争；退款查询与财务读取。测试账户不能触发的Dispute/Payout/报告能力明确BLOCKED或fixture-only及原因，相关AC不得PASS；本地负载和大规模故障仍用PR04。
验收ID：AC-V1-05、06、07的适配器契约部分；其他读取为后续PR提供已验证或明确受阻的契约。保存版本、运行时间、脱敏对象引用和实测/fixture矩阵。
运行构建、adapter单测及实际可运行契约，报告命令和结果；未运行NOT RUN，不适用N/A，环境受阻BLOCKED。不得为通过而改成Fake替代真实测试结论。
```

## PR06：业务订单与支付创建、查询、取消

```text
执行 PR06：业务订单与支付最小链路。依赖PR02、PR04、PR05。读取docs/product PRD/验收和冻结OpenAPI、状态机、锁序，检查工作区，列实体/迁移/API/事务/测试计划后实施。完全从零，允许修改已有adapter和认证接线，但不扩大产品范围。

新增business_order、payment、api_idempotency_record、provider_operation、payment_attempt及持久执行义务。provider_operation关联方式遵守PR00：payment/refund互斥关联时预留退款列并在PR11加FK，或采用有约束关联表；不要创建不可扩展的支付专用操作模型。补齐scope、fingerprint、操作级稳定key、审计、版本与条件回写字段。
business_order金额来自可信应用与受控商品/价格校验，币种和数量有边界；客户端金额不能覆盖权威价格。下单即保存分润政策版本和完整不可变JSON快照，不能等PR12才读取最新政策。业务order_key与API Idempotency-Key独立：不同API键对同订单也不能重复建支付；同键不同语义内容409 IDEMPOTENCY_CONFLICT。
实现POST /v1/payments、GET /v1/payments/{id}、POST /v1/payments/{id}/cancel及最小客户托管跳转/结果查询入口。返回paymentStatus、providerStatus、resolutionStatus、financialStatus、确认时间、资源ID和url/nextAction；创建Checkout不宣称已扣款。保存多attempt，卡拒绝首先影响attempt，成功capture事实不被退款/取消抹掉。

事务：订单+幂等记录+payment+provider_operation+持久执行义务同一短事务提交；最小执行器先领取再在事务外调用Provider，凭操作/版本条件回写。未提交意图不得先发请求。取消先持久化意图，按Checkout真实过期语义执行；已受理或有竞争时保持UNKNOWN/CONFLICT并留证。PR07增强租约和恢复，本PR不能只依赖内存线程提交后必执行。
测试：100并发同API键；不同API键同order；同键异payload；客户端伪造金额/账户；整数溢出；政策更新不影响已有快照；创建Session没有扣款；多attempt只关联原payment；取消与成功竞争；提交后执行前中断仍留持久义务。真实Checkout最小跳转与结果查询记录独立于Fake结果。
验收ID：AC-V1-03、04、05、06、07的支付层，剩余恢复/事件接线标注PR07/08。不得将尚未恢复的UNKNOWN标为完整通过。
运行构建、当前PostgreSQL并发/API测试、隔离空库与升级迁移、适用真实契约及diff检查，报告实际命令与AC证据；NOT RUN不能PASS。不提前做退款或分润计提。
```

## PR07：通用操作恢复与支付 L1/L2

```text
执行 PR07：通用provider_operation恢复、租约和支付L1/L2。依赖PR04、PR06。读取docs/product PRD/验收、操作状态机和PR00锁序，检查工作区，列计划后实施。复用已有持久执行义务，不创建第二套支付模型。

扩展provider_operation：lease_owner、lease_until、lease_token/version、attempt_count、last_query_at、resolution、last_provider_status、原始idempotency_key、提交/查询证据。当前唯一创建recovery_task（scope、task_type、resource_id、唯一执行资格、调度、租约、状态、证据）；PR11退款和PR15 L3复用此表/框架。
实现OperationLeaseService、OperationRecoveryCoordinator、ProviderQueryService、L1延迟查询和L2本地扫描。默认L1 5/15/30/60/120秒带抖动和账户限流，L2每5分钟扫描UNKNOWN、过期租约、已知缺证据；均可配置并注入Clock。无新增公共写API，既有GET payment展示恢复状态与最后确认时间。

事务：短事务原子claim并发放lease_token→事务外Provider调用/查询→只有当前token和合法状态能回写。过期Worker的返回保留为观察证据，不能覆盖新租约结果，也不能直接丢掉可能重要的成功对象ID。任务创建/状态变更与资源执行义务原子一致；重领幂等且有退避/年龄告警。
未知结果先查原外部ID及可信关联；窗口内的安全重试仍使用原操作key。已过验证保留窗口且无ID，不盲目换key或重发同key创建Checkout/PI/Refund；不能排除已受理时保留UNKNOWN、挂起升级和报警。只有可证明确未发送/未执行的路径才按规则再次发送。
测试：调用前/远端受理后/本地回写前/查询后崩溃；两个Worker竞争；旧Worker越租约回写；查询暂时空后成功；取消成功竞争；幂等窗口过期且远端已有资金效果；数据库事务内无网络等待。用独立Fake效果计数证明没有重复效果，不只数本地payment。
验收ID：AC-V1-07、08、09、10支付部分。退款恢复在PR11接入，本PR保留类型扩展契约。
运行构建、真实PostgreSQL多Worker/时钟测试、隔离迁移与diff检查。报告命令、租约边界证据、挂起条件和未运行项；适用未运行NOT RUN，不适用N/A。
```

## PR08：Webhook Inbox 与规范观察

```text
执行 PR08：验签、持久Inbox、异步规范观察与支付生效。依赖PR05、PR06、PR07。读docs/product PRD/验收及状态/财务边界，检查工作区后列计划并实施。允许修改支付与恢复入口接入统一观察处理，避免多个入口各自覆盖状态。

新增webhook_inbox、normalized_observation/event、dependency_wait；事件唯一键含Provider账户与环境，规范语义key依对象与事实类型定义，不能把每个eventId当独立财务事件。保存必要原始字节/摘要、验签结果、接收与处理状态、依赖和审计；安全保留策略避免无边界PII存储。
新增POST /webhooks/stripe；StripeWebhookVerifier使用原始字节验签后解析。先验证签名和真实endpoint账户范围，再短事务唯一写Inbox，持久化成功才2xx；失败返回可重试错误，非法签名无业务副作用。重复已持久事件可以2xx。
异步Normalizer/Processor接入payment与attempt状态、Checkout/PI/Charge关系和恢复证据；退款、争议、payout先保留可处理观察/依赖，分别PR11/13/14完成业务接线。Webhook、查询结果和后续BT扫描走共同规范处理，不按到达先后覆盖已知事实。

事务：claimInbox后在短处理事务记录规范观察、条件更新支付与依赖；并发Handler遵守PR00锁序。客户返回成功、HTTP200、Session completed和PI成功的含义逐项判断。支付确认成功但缺BT时financialStatus=PENDING_EVIDENCE，不生成PSP Journal、不猜手续费；PR10负责正式资金入账。PR09会将支付变更与Outbox接为同一事务。
测试：字节篡改/非法签名无副作用；数据库不可用不返回假成功；同event重放和不同event同语义双层去重；Charge/PI乱序、依赖不存在、等待后恢复；晚到旧状态不回退成功；未知冲突保留查询证据；同观察经Webhook和查询进入不会重复应用。
验收ID：AC-V1-06、07、11、12、13；AC-V1-12财务去重部分由PR10闭合，其他资源依赖等待在PR11/13闭合。
运行构建、原始签名fixture测试、PostgreSQL持久/并发/API测试、隔离迁移和diff检查。报告真实签名fixture与本地签名模拟区别，未运行NOT RUN、不适用N/A。
```

## PR09：Outbox、Kafka 与商户通知

```text
执行 PR09：支付Transactional Outbox、Kafka、Consumer Inbox和商户通知。依赖PR06、PR08。读取docs/product PRD/验收、消息契约和现有支付事务，检查工作区后列计划并实施。当前接入支付，PR11才接入退款；不能宣称不存在的Refund状态已原子联动。

新增outbox_event、consumer_inbox、delivery_task及必要发布租约、退避/死信和目的地址配置。实现OutboxWriter/Publisher、ConsumerInboxService、MerchantNotifier、模拟商户及其持久履约去重。事件有稳定eventId、类型、资源、版本、scope和最少必要payload。内部消息和商户通知契约补齐；无新增公开资金写API。

事务：所有确认支付业务状态的入口（Webhook、查询恢复、同步观察）与Outbox写入在同一本地事务；不要在commit后只发内存事件。发布租约事务后向Kafka发送，允许发送成功但完成标记丢失造成重复。消费者Inbox去重和本地业务效果同事务，提交后ack。delivery_task创建与消费效果同事务，HTTP通知在事务外。
模拟商户收到稳定业务键后先幂等持久履约再2xx；商户履约后丢响应可重发但效果一次。这依赖商户实现幂等，不能声称控制任意第三方exactly-once。通知目标限制内部网络/重定向等非预期访问，密钥和签名材料不进入日志。
保留后续回补的履约资格判断接口：只有仍有效且可唯一关联的原订单可以产生成功通知；PR15将接入。历史恢复不自动意味重新发货。

测试：支付提交后发布前崩溃；发布后标记前崩溃；消费提交前/后崩溃；Kafka中断恢复；重投；商户履约后响应丢失；两个消费者竞争；永久失败可见且有退避。验证本地Outbox无漏写、模拟商户独立效果计数一次。
验收ID：AC-V1-14、15支付通知部分；退款接线PR11、L3履约资格PR15。基础日志/积压指标现在加入，不等PR17。
运行构建、PostgreSQL/Kafka集成与故障边界测试、隔离迁移和diff检查，报告实际命令和证据。NOT RUN不能算PASS；不适用N/A。
```
## PR10：BT 导入与资金入账

```text
执行 PR10：Balance Transaction导入、完整性与PSP资金入账。依赖PR03、PR05、PR08、PR09。读取docs/product PRD/验收和会计/AccountScope契约，检查工作区后列计划并实施。可修改观察处理、账本及消息接线；不新增绕过过账服务的余额更新。

新增provider_observation、balance_transaction、import_window/import_cursor、quarantine_observation和证据冲突记录；必要时复用已有规范观察。保存组织/Provider账户/livemode、BT ID、amount/fee/net、currency、source、balance domain、created/available_on、原始摘要/版本/抓取时间。外部财务去重键固定为组织+Provider账户+livemode+BT ID，不把currency当可变去重维度；同BT改币种/金额属于冲突，不能因此新建另一事件。
实现Importer、FinancialEventNormalizer、BTJournalMapper。当前先提供内部调度/受控测试调用，无公共任意资金导入API；PR16接运营命令。Charge和Refund BT基础映射都实现；退款业务尚不存在时保留PENDING_EVIDENCE/依赖等待，由PR11接通。争议/payout对象及本金解释不足时同样保留事实，分别PR13/14完成。

事务：固定scope与created窗口分页，页面观察/游标持久化；分页全部完成才能推进完成水位。分类接受资金事实时，按统一锁序同事务写规范financial_event、平衡Journal/Entry、projection和需要的Outbox。projection只由LedgerPostingService更新，Importer调用后不能再加一次PSP余额。证据不足不得伪造交易归属。
验证net=amount-fee及符号政策。已确认本账户payments域且净资金事实可验证、但业务类型未知时，PSP↔SUSPENSE反映实际资金，再等待审批重分类；其他域、FX/归属不足或冲突进入隔离，不能混入payments余额。pending→available只刷新可用性维度，不能重复增加PSP总额或收款收入。已入账金额冲突不能修改旧Journal。
窗口使用业务时区生成UTC边界；固定高水位、分页、重叠回看和pending跟踪分别记录。默认回看7业务日是项目策略，历史backfill可指定；已有账户期初与历史BT不能重叠双计。

测试：分页中断/恢复/缺页、重复导入/并发导入、同ID改金额/币种、Charge与Refund分录、退款缺依赖后重放、未知已验证payments类型暂记、跨域隔离、pending/available刷新、DST日期边界与JPYfixture。独立扫描验证一BT一财务Journal、投影没有重复加净额。
验收ID：AC-V1-12、13、16、17、18、21基础映射、30；PR11完成退款资金业务闭环，PR15补独立完整性对账。状态成功但缺BT不能通过资金验收。
运行构建、真实PostgreSQL分页/账本/并发测试、隔离迁移和diff检查，报告实际命令与证据；未运行NOT RUN，不适用N/A。
```

## PR11：退款预占、恢复、通知与资金闭环

```text
执行 PR11：完整退款闭环。依赖PR06～PR10。读取docs/product PRD/验收、退款状态机和锁序，检查已有操作/恢复/Inbox/Outbox/BT入口，列接线计划后实施。共享PR07恢复框架，不再创建独立refund_operation或第二张recovery_task。

新增refund_control、refund及退款来源/关联记录；按PR00方案为通用provider_operation添加refund FK或约束关联。C=确认capture，R=成功退款累计，H=未明确结束预占；NORMAL时0<=R+H<=C。补齐scope、请求fingerprint、稳定幂等键、providerRefundId、resolution、证据与版本。
实现POST /v1/refunds、GET /v1/refunds/{id}；接退款Webhook/查询观察、通用Operation执行与租约恢复、成功/失败Outbox、PR10退款BT及失败返还BT，不能只提交退款创建接口。Provider调用在事务外。

预占事务：锁payment/refund_control→校验资格与金额→增加H→创建refund、通用operation、持久义务→提交。成功条件更新H转R；明确失败/取消释放H；PENDING/REQUIRES_ACTION/UNKNOWN保留H。确定状态与Outbox同事务，重复观察不重复完成/释放。预占本身不产生PSP分录，实际资金仅依据BT；失败返还有新BT时产生新Journal，不能删除退款旧账，费用不按比例猜测返还。
Dashboard外部退款也摄入；先保存权威外部事实与资金证据，再检查是否对应既有预占。存在冲突则control_state=EXTERNAL_CONFLICT，停止新退款并调查；不能丢弃外部事实维持乐观额度，也不能把同一退款计入R和H两次。
本阶段分润未启用，明确功能门槛：PR12必须把退款预占/成功/失败/取消/恢复/外部冲突全路径接入同事务分润调整后才启用分润。争议阻断策略接口预留，实际接入与验收在PR13；不得宣称现在已验证争议限制。

测试：C=1000时600+600并发只批准一个；同键重复/异payload；重复成功/失败；成功后旧pending；受理后超时保留H；过期key不盲重发；租约重领与旧Worker回写；外部退款重叠；退款BT先于状态/业务；失败返还与费用证据；商户重复通知效果一次。至少在真实PostgreSQL用屏障测试控制行竞争，不等PR18。
验收ID：AC-V1-09、10、13、19、20、21；补齐AC-V1-14/15退款消息语义。PR12/13跨域条件单列未启用/待接线，不冒认通过。
运行构建、数据库/API/恢复/BT/通知集成测试、隔离空库及升级迁移、少量适用真实退款契约、diff检查。报告实际命令与结果；适用未运行NOT RUN，不适用N/A。
```

## PR12：内部分润计提及退款原子联动

```text
执行 PR12：内部revenue share账务闭环。依赖PR03、PR06、PR10、PR11。读取docs/product PRD/验收、下单快照和账本政策，检查工作区及退款所有处理入口，列迁移/事务/事件键/测试后实施。允许修改PR11代码完成接线，不能只增加计算器。

新增share_allocation、share_adjustment/history及必要快照引用；读取PR06已保存的不可变政策JSON和版本，不用当前最新配置回填旧订单。若需share_policy实体，作为下单快照的规范引用而非延迟改变原意图。无合作方实付API，无Connect调用；当前内部查询服务供PR16开放。
政策：比例基点合计<=10000；只有收款BT已正式入账、无资金冲突或阻断争议时eligibleBase=max(0,C-R-H)，各cap=floor(eligibleBase*rateBps/10000)，余数归平台，有效S=历史计提-撤回且0<=S<=cap。使用精确整数与溢出检查，按当前累计base算cap，不能逐笔退款取整后累加。

账务：初次计提、退款预占撤回、明确失败/取消释放后的恢复都必须有稳定内部事件key、新Journal和allocation同事务。遵循PR00账户政策，计提表现为内部成本/合作方应付，撤回反向追加；不变更PSP资产，不修改旧凭证。可使用业务事实版本/原因ID生成稳定key，不能每次重试随机生成事件身份。
事务：锁同一payment/refund_control及allocation→变更C/R/H或阻断状态→按新cap追加所需财务事件/Journal并更新allocation→Outbox/业务状态→提交。接入PR11预占、成功H转R、失败/取消、UNKNOWN后恢复、外部冲突全路径，H转R不能重复撤回；明确失败恢复只一次。
收款BT第一次入账后驱动初次计提；已有历史收款BT的支付通过幂等catch-up补计提。应用启用分润前必须完成上述接线与测试；不能靠以后定时任务修正提交后已超cap的状态。预留争议阻断接口，PR13完成争议触发与胜诉恢复验收。

测试：C=10000、20%计提2000，H=4000后cap1200/撤回800，失败释放恢复800，成功H转R不再撤回；碎片退款舍入；多合作方比例上限；BT未到不计提；历史BT catch-up重跑；退款/计提并发；异常注入在allocation与Journal之间完整回滚；重复恢复与直接旧账修改拒绝。数据库测试验证金额变化与追加Journal一一对应。
验收ID：AC-V1-22、23本阶段；AC-V1-23的争议恢复部分PR13完成。I-04上限是同事务保护，不只夜间报警。
运行构建、独立手算/属性基础模型、真实PostgreSQL并发/回滚/账务测试、隔离迁移和diff检查，报告实际结果及启用门槛；NOT RUN不能PASS，不适用N/A。
```

## PR13：争议、资金事实与分润阻断

```text
执行 PR13：Dispute完整生命周期及跨域接线。依赖PR08、PR10、PR11、PR12。读取docs/product PRD/验收、争议状态机/会计政策/锁序，检查退款和分润入口后列计划并实施。当前需修改已有处理器完成接线，不能留下独立无调用的DisputeService。

新增dispute、dispute_financial_fact、dispute_case/evidence及必要控制关联，保留原provider状态、多个dispute/charge关系、金额币种、deadline与证据。接入Webhook、查询、BT依赖恢复；提供内部查询/运营服务供PR16，不自动提交抗辩材料，不新增真实资金写接口。

资金：inquiry/warning本身不扣PSP；正式扣款BT按实际本金借DISPUTE_RECEIVABLE、费用单列、贷PSP_FUNDS。胜诉返还新BT恢复PSP；败诉只将仍存应收重分类DISPUTE_LOSS，不能再扣一次PSP。late win按返还证据先冲尚存应收，再冲此前已确认损失，超额进入SUSPENSE/差错，不能凭状态恢复无证据金额。费用及费用返还按实际BT，不硬编码胜诉全退费用。
业务：未决争议、败诉或未解决资金矛盾使eligibleBase=0，追加撤回内部计提；开放争议阻止本系统新发普通退款。外部退款与争议重叠照实保存、冻结调查，不把争议额直接加进R，也不假设一个Charge只有一项争议或争议金额必<=原额。胜诉且相关资金/账务证据齐全、无其他阻断后才恢复分润。

事务：争议规范观察、支付控制/阻断、分润allocation与追加Journal按共同锁序原子提交；财务事件经PR10/PR03稳定BT键唯一过账。状态先到/资金先到都保留依赖，不靠消息顺序猜测。内部损失重分类也有稳定业务event_key，重放不得重复重分类。
测试：inquiry、正式扣款、状态/BT先后、重复事件、won返还、lost不再扣PSP、late win应收/损失/超额分配、多争议、退款与争议并发、外部重叠事实、资金未齐不恢复分润、最后阻断清除后恢复一次、deadline展示。真实账户无法触发路径时明确fixture-only，不冒充真实争议实验。
验收ID：AC-V1-13、23、24、25。运行构建、真实PostgreSQL事务/并发/财务测试、适用契约、隔离迁移和diff检查。报告实际命令、分录证据和未运行项；NOT RUN不能PASS，不适用N/A。
```

## PR14：Payout 与模拟银行核对

```text
执行 PR14：Payout摄入查询、银行fixture及匹配。依赖PR03、PR05、PR08、PR10。读取docs/product PRD/验收及payout会计/状态机，检查工作区，列实体、分录、冲突和测试计划后实施。仅测试资金；摄入查询必做，主动test payout仅账户能力支持时可选，缺该可选能力不虚构实测。

新增payout、payout_fact、bank_statement/bank_statement_line、payout_bank_match及本金分配控制、来源证据/导入批次。接Payout查询、Webhook、BT/failureBT；实现BankStatementImporter核心与BankMatcher内部服务，PR16再暴露受权限保护的导入API。银行schema固定、fixture ID稳定，数据/页面/报告明确simulated bank evidence。

资金：payout扣减BT借BANK_PAYOUT_IN_TRANSIT、贷PSP_FUNDS；真实费用分离。payout.paid只改提供方状态，不能直接借BANK_CASH。可信银行入账匹配才借BANK_CASH、贷在途；失败返还按新BT借PSP、贷在途。原Journal不删改。
单payout已转出本金=尚在途+已确认银行本金+已确认返还本金（含验证调整）；本地分配事务可立即拒绝超额。银行先匹配后出现返还/失败，或二者交叠时保存冲突事实，不能两边都算满额、不能凭失败再冲一遍银行现金。费用不占用本金或被重复扣除。
自动payout只有提供方给出归属时做所含交易映射；手动payout不靠FIFO猜官方订单归属。窗口包含的payout负BT与所含交易净额分别解释，避免批次汇总重复抵扣。没有完整银行证据只WAITING_EVIDENCE，不能直接叫SHORT_RECEIPT。

事务：匹配锁payout分配控制和银行行→检查scope/币种/参考/日期窗/可分配金额→追加稳定匹配事件Journal与分配→提交；一银行行不能被重复消耗，重导/重试幂等。返还观察使用同锁序检查重叠。所有projection仍只由LedgerPostingService更新。
测试：paid无银行记录、部分匹配、合法拆分、重复导入/匹配、短款、银行费用、失败返还、银行到账后返还交叠、币种错、未知状态、自动/手动归属、并发超额分配。检查I-06本地上限与证据完整后的外部守恒分别成立。
验收ID：AC-V1-26、27本阶段；费用/短款完整证据和对账PR15闭合。运行构建、数据库/会计/导入集成、适用真实读取契约、隔离迁移和diff检查。报告真实/fixture范围与实际命令，未运行NOT RUN，不适用N/A。
```

## PR15：L3 回补、三层对账与夜间检查

```text
执行 PR15：L3 缺单回补、交易/余额/payout 三层对账、费用模型和夜间七项检查。依赖PR07、PR09–PR14。读取docs/product PRD、验收矩阵、对账状态机与锁序，检查现有恢复任务，不新建第二套recovery_task或第二套账本投影。列明窗口、证据版本、事务和测试后实施。

新增/扩展reconciliation_run、reconciliation_cursor、reconciliation_exception、fee_model和夜间检查结果；复用PR07的任务租约和PR03的重建入口。保存scope、UTC窗口、业务时区、provider cursor、页面完成度、高水位、独立证据版本、规则版本、residual和INCOMPLETE原因。页面未完成、游标缺失、范围不一致或独立期末不可比时不能COMPLETE/PASS。

实现：L1已知操作轮询由PR07负责；L2继续扫描UNKNOWN、缺BT、老化预占和过期租约；L3按业务时区将明确的T+1 UTC窗口分页枚举Stripe BT和来源对象，支持重叠回看、断点、手动backfill和晚到重跑。L2五分钟是兜底发现，不替代租约到期领取循环。时间窗口不能用固定减24小时代替时区/DST转换。

自动回补只在账户、environment/livemode、币种、成功capture、金额、唯一可信业务订单、服务端归属证据全部满足时创建本地关联，并带origin=RECONCILIATION、runId、source BT和证据摘要；只有BT没有payment但有有效订单时恢复关联；连订单也没有只保存未分配财务观察，不创建订单、履约或猜测归属。任一歧义、同金额多订单、metadata单独命中、金额冲突进入差错。

交易级比较外部对象与本地资源；余额级比较期初+完整BT、Ledger重建和同scope同截止点的独立报告/受控静止快照；payout/银行级比较本金、在途、确认银行本金、返还和费用。BT生成的账本与同一BT求和只能检查本地映射，不能独立证明源数据完整。缺独立证据为INCOMPLETE，不制造平账分录。已验证payments域未知类型按PR10的PSP↔SUSPENSE检查覆盖，其他域仍隔离。

实现版本化费率预期和UNMODELED_FEE；实际BT/银行费用是权威事实，模型不覆盖或抵消实际分录。实现夜间I-01～I-07检查：试算/封存与覆盖、退款预占老化、分润上限、BT分页覆盖、PSP余额、payout在途、投影独立重建；结果分别PASS、FAIL或INCOMPLETE，固定输入水位/runId/证据版本/残差/负责人。确定重复、漏账、不平衡或超额为FAIL，缺证据不是PASS。

测试：丢Webhook已知单、完全缺payment、有/无可信order、分页缺页/断点/重复、晚到BT、同BT冲突、不同币种/账户、pending→available、独立期末漏退款、费率模型不足、DST边界、期初Journal不重复、payout部分匹配/返还和夜间复发。使用独立外部fixture，不用生产计算函数作为oracle。验收ID：AC-V1-15、16、27、28、29、30、33。运行构建、PostgreSQL/Provider fixture集成、隔离迁移和diff检查；适用未运行NOT RUN，不适用N/A，证据不足标INCOMPLETE。
```

## PR16：运营 API、审批与审计

```text
执行 PR16：运营查询、对账/银行导入、异常动作、人工调整审批和审计。依赖PR02、PR11–PR15。读取冻结OpenAPI、RBAC/审计ADR和现有领域服务，列出每个endpoint的scope、权限、幂等、事务和错误后实施；页面不直接访问数据库。

实现只读API：GET /ops/payments/{id}、/refunds/{id}、/disputes/{id}、/payouts/{id}、/revenue-shares/{id}、/ledger/journals、/ledger/journals/{id}、/balances、/reconciliation-runs/{id}、/exceptions、/tasks。详情要能沿payment→Session/PI/Charge→BT→Journal→bank evidence追踪，并展示providerStatus、resolution、financialStatus、PENDING_EVIDENCE、UNKNOWN和INCOMPLETE，不把HTTP成功解释成资金成功。

实现写API：POST /ops/reconciliation-runs、POST /ops/bank-statements/import、POST /ops/exceptions/{id}/actions、POST /ops/tasks/{id}/retry、POST /ops/adjustments、POST /ops/adjustments/{id}/approve。请求必须含scope、理由、证据引用、稳定幂等键和目标；导入文件保存fixtureId/摘要/版本，重复文件不重复匹配。重试只创建/唤醒既有任务，不绕过UNKNOWN、范围或过期key规则。

RBAC由可信运营身份解析organization/environment；查询和写操作默认拒绝跨域。调整作者不能审批自己；审批状态条件更新且重复审批不重复凭证；只有审批完成的人工调整才能通过LedgerPostingService追加reversal/adjustment Journal，绝不更新、删除或补插旧Journal。差错解决必须关联证据、命令或resolutionJournalId，不能仅把状态改为RESOLVED。

audit_log记录actor、role、action、target、scope、requestId、reason、evidence、before/after摘要、result和时间；不记录secret、卡号、完整token、原始敏感银行字段。所有运营写操作和拒绝均审计，审计本身不可被业务用户删除。

测试：双组织/环境/币种越权、伪造路径参数、查询与写接口权限、创建者自审、重复审批/导入/重试、无证据调整、已过账SQL修改、异常处理和错误响应、审计脱敏、同scope并发操作。验收ID：AC-V1-01、31；PR15的证据/夜间/回补在本PR只暴露，不重新实现。运行构建、API/RBAC/PostgreSQL测试、OpenAPI解析、隔离迁移和diff检查；NOT RUN不能PASS。
```

## PR17：客户结果页、运营页面与观测

```text
执行 PR17：最小客户结果页、运营页面、结构化日志、指标和时间线。依赖PR16。先检查已交付API的响应和权限，页面只能调用API，不能直接连接数据库或写资金表；只实现本项目范围，不建设营销站或通用ERP。

页面：Payment Detail、Payment Result/UNKNOWN、Refund Detail、Dispute Detail、Ledger Explorer、Balance Reconciliation、Payout Detail、Revenue Share、Exceptions/Tasks。详情页展示时间线和证据链：业务订单、Checkout/PI/Charge attempt、provider状态、BT、Journal/Entry、银行fixture；明确UNKNOWN、PENDING_EVIDENCE、SUCCEEDED、FAILED、INCOMPLETE、WAITING_EVIDENCE、CONFLICT。

增加结构化日志与指标：unknown_payment_count/oldest_age、refund_reserved_age、webhook_lag、outbox_oldest_age、ledger_pending_evidence、unclassified_bt_count、trial_balance_failures、reconciliation_residual、payout_unmatched_age、share_cap_violations。日志关联businessOrderId/paymentId/operationId/providerRequestId/BT/event/Journal/runId，但脱敏API key、client secret、卡号、银行敏感字段和完整token。

测试：页面smoke、UNKNOWN和证据不足展示、跨scope API拒绝、响应字段与OpenAPI一致、日志脱敏、指标采集、旧状态/重复通知不会造成页面跳转误导。测试页面显示的“成功”仅在本地资源/财务证据语义满足时使用。验收ID：AC-V1-15、32；运行前端/API smoke、观测检查、构建和diff，未运行项NOT RUN。
```

## PR18：属性模型、组合序列与真实数据库并发

```text
执行 PR18：增加独立模型与组合测试，不修改业务规则迎合测试。依赖PR11–PR17；基础账本/退款/分润/重建测试必须已经随相应PR存在。

创建独立的PaymentModel、RefundModel、RevenueShareModel、LedgerModel、PayoutModel、ReconciliationModel和generator/shrinker。oracle自行计算C/R/H、cap、资金变化、在途分配、事件唯一性和重建结果，不调用生产计算函数作为期望。生成合法与应拒绝命令，包括重复/乱序、崩溃重试、key过期、退款与争议/分润/payout竞争、跨scope输入和缺证据。

每个本地事务后检查I-01、I-02提交唯一性、I-03、I-04和I-07可立即成立的部分；外部证据完整且cutoff一致后检查I-05/I-06。串行属性序列不代替并发：使用真实PostgreSQL屏障、多个API/Worker、固定锁序和重复提交断言。默认PR至少100 seeds、每序列最多100步，夜间默认1000 seeds、最多300步；实际数量、耗时、环境和CI预算如实记录。

保存失败seed、完整操作序列、shrinker最小反例、数据库/Provider/消息证据、修复commit和回归测试。可做mutation敏感性实验，但单独标为mutation；不得把人为注入问题写成真实缺陷。真实缺陷允许最小生产代码修复或独立修复PR，不能改不变量或oracle使测试通过。验收ID：AC-V1-34及各资金验收扩展。运行构建、属性测试、真实PostgreSQL并发、隔离迁移和diff检查；报告实际seed和NOT RUN。
```

## PR19：八类故障实验与 CI 证据

```text
执行 PR19：只增加可重放故障脚本、CI矩阵和实验报告，不修改业务规则迎合实验。依赖PR07–PR18。所有实验使用Fake/fixture或少量契约测试，禁止live key、真实资金和Stripe沙箱负载压测。

覆盖F-01丢Webhook、F-02重复Webhook、F-03乱序Webhook、F-04篡改/非法签名、F-05 Provider发送前失败/受理后超时、F-06事务提交前后和进程崩溃、F-07 Kafka中断/重复投递/消费者崩溃、F-08并发支付/退款/分润/匹配。每类指定注入点、固定时钟或seed、数据集、操作键和恢复入口。

每份实验保存Title/Test ID、commit SHA、环境/版本/数据量、seed、预期、注入点、复现命令、实际观察、数据库/Provider/消息证据、请求数/受理数/Webhook数/状态转换数/Journal数/商户效果数/真实模拟资金效果数、恢复耗时、限制。分别说明Fake行为、Stripe契约行为和模拟银行证据，不能合并成“真实生产验证”。

验收零重复模拟资金效果、零本地发起超额退款、零重复规范Journal、零不平衡Journal；若失败，记录FAIL和根因，不隐瞒或只改报告。恢复时间只在环境、依赖、队列和账户限流条件下表述；120秒是PRD限定条件的本地任务重领目标，不是外部争议或支付最终完成承诺。验收ID：AC-V1-35。运行CI故障脚本、报告生成、构建和diff；未执行用NOT RUN，环境能力受阻用BLOCKED。
```

## PR20：性能、备份恢复与英文成果

```text
执行 PR20：性能实验、隔离测试库备份恢复、英文README/架构图/演示/故障复盘/简历证据映射。依赖PR15、PR17–PR19。先读取验收中的目标和限制，列出设备、JVM、PostgreSQL、Kafka、数据量、commit和命令后实施；没有实测数字不得填入材料。

性能只对本地模拟Provider和明确测试环境：双API、双Worker、100万历史支付，预热5分钟，运行30分钟，写入受理100 RPS、查询200 RPS为目标场景；记录P50/P95/P99、错误率、DB资源、Kafka lag、任务积压、Provider请求数、受理数、业务效果数和Journal数。P95≤300ms、P99≤1s、非注入错误<0.1%是待验证目标，不是项目已取得成绩；队列增长或正确性失败即使延迟达标也不能称完整通过。不要向Stripe测试环境发送负载测试。

恢复只在新工程明确隔离的测试库执行：核验目标库名/连接和外部模拟器持久状态→做备份→恢复到隔离实例→校验迁移/权限→停止写入→重建投影和试算→回放Outbox/事件→按scope/窗口重扫Provider fixture或Stripe测试范围→核对BT覆盖、未知操作、消息积压和独立余额证据→满足门槛后恢复写入。记录RPO/RTO、损失窗口、未自动恢复数据和实际命令；不删除、覆盖或迁移其他项目数据库。

英文材料必须区分设计、Fake、Stripe测试契约、模拟银行和未验证能力，所有简历数字带commit/环境/数据量/命令/报告位置。生成README、架构/状态/账本图、10分钟演示脚本、故障复盘、恢复手册和resume-evidence-map.md；不写生产规模、真实合作方结算、Stripe生产性能或“发现N个缺陷”除非证据存在。验收ID：AC-V1-36、37、38。运行适用性能/恢复/文档链接检查、构建和diff；未运行项NOT RUN，性能目标未达标记录FAIL与原因，不改目标掩盖结果。
```
