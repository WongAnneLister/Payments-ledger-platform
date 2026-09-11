# Stripe Payment Gateway & Double-Entry Ledger — PRD

版本：1.1 · 更新日期：2026-09-10 · 状态：从零实施的设计基线；代码、测试和账户能力尚未验证
技术方向：Java、PostgreSQL、Redis、Kafka、Stripe
关联文档：[实施规划](payment-platform-roadmap.md) · [验收与故障实验](payment-platform-acceptance.md) · [PR 执行提示词](stripe-payment-ledger-codex-pr-prompts.md) · [面试手册](payment-platform-interview-questions.md)

本套 1.1 文档为新工程的唯一设计基线。验收编号使用 AC-V1-01～AC-V1-38；旧版 AC 编号不再引用。PRD 定义业务规则，验收表定义验证方法，路线图和提示词定义实施归属；发现冲突先同步修订，不能从多个版本中挑选方便的规则。

## 0. 执行摘要：为什么做这个项目

我们要解决的业务问题是：

> 一个通过 Stripe 收款的数字商品平台，如何在请求超时、事件丢失、退款、争议、费用和银行出款不断发生时，持续回答“客户是否付了款、钱怎么变化、现在在哪里、我们应得多少、差额在哪里”，并让答案可复算、可追溯、可恢复？

Stripe 负责实际支付处理，本系统负责把提供方事实变成可靠的内部交易记录、账务记录和异常处理工作流。平台有一个明确的销售主体，商品可能由合作方提供，平台按约定比例计提合作方分润。首先支持一个 Stripe 测试账户、一个结算币种及合作方内部应付账款，不把多商户聚合收单、Stripe Connect 和平台银行出款混为一谈。

项目不是为了堆砌微服务，也不只是给 Stripe SDK 包一层 Controller。其价值在四个闭环：

1. 收款成功但本地不知道：发现并恢复交易事实。
2. 交易记录正确但钱对不上：通过独立账本和余额对账定位差异。
3. 退款或争议改变了收入：重新计算资金约束和分润资格。
4. 软件声称可靠但没有证据：用不变量、属性测试和故障注入验证。

项目面向支付后端岗位的工程能力展示，包括赴日求职场景；不与任何目标雇主关联，不推断其未公开系统或面试题。Java 用于实现，资金正确性、恢复证据和解释能力是展示重点。

完全从零实现：在独立新仓库设计代码、数据表、接口、状态机、测试和运行环境。无旧系统代码复用、数据迁移、兼容层或旧流程研究依赖。payout 定义为 Stripe 账户向平台银行账户出款。本文档交付本身不表示新仓库已创建。

## 1. 从第一性原理推导需求

### 1.1 原理、业务后果与需求

| 基本事实 | 如果忽略会发生什么 | 推导出的需求 | 验收依据 |
|---|---|---|---|
| 网络响应和远端执行不是原子事件 | 客户已扣款，本地显示失败，重试造成重复操作 | 幂等、持久化执行意图、UNKNOWN、查询恢复 | F-05、AC-V1-04、AC-V1-08、AC-V1-09 |
| Webhook 是消息，不是数据库事务 | 重复记账、乱序覆盖、丢事件后永远缺单 | 验签后持久化 Inbox，语义去重，外部扫描兜底 | F-01～04、AC-V1-11、AC-V1-12、AC-V1-13 |
| 一笔支付有多个阶段和多个对象 | HTTP 200、支付成功、余额可用、银行到账被当成一回事 | 交易、财务、可用性、payout、银行确认分别建模 | AC-V1-05、AC-V1-06、AC-V1-26 |
| 资金变化需要有来源和去向 | 余额可被任意改动，无法解释差额 | 复式分录、不可变凭证、冲正和业务来源关联 | I-01/02/07 |
| 退款会降低有效收入 | 分润仍按原金额计算，平台超额承诺给合作方 | 退款额度预占、分润上限随退款原子调整 | I-03/04 |
| 争议是另一条资金生命周期 | 开案和败诉重复扣款，胜诉却漏记恢复 | 状态与实际资金移动分离，以外部资金记录记账 | AC-V1-24、AC-V1-25 |
| PSP 持有的钱尚未在银行 | 把净额出款差异误当收入变化，漏掉费用 | PSP 资产、出款在途、银行证据及余额对账 | I-05/06 |
| 本地扫描只能找到本地已有订单 | 已在 Stripe 成功、本地整条记录缺失的支付永远找不到 | 从 Stripe Balance Transactions 反向发现并谨慎回补 | AC-V1-28、AC-V1-29 |
| 例子有限，事件组合接近无限 | 单测全绿但退款/争议/分润组合出错 | 基于独立模型的属性测试、缩减反例 | AC-V1-34 |
| 最终一致需要明确收敛条件 | 重试队列积压被误称为已解决 | 收敛期限、证据完整度、差错工单与告警 | AC-V1-10、AC-V1-29、AC-V1-33 |

### 1.2 系统始终要回答的五个问题

- 这笔支付在 Stripe 和本地分别是什么状态？结果最近一次确认于何时？
- 这笔业务造成了哪些借贷分录，每条来自哪个外部资金变动或内部决策？
- Stripe 还持有多少净资金，其中多少 pending、多少 available？
- 已发出的 payout 有多少仍在途中，多少有银行到账证据，差多少？
- 合作方可以被计提多少分润，退款和争议后是否仍满足上限？

只要有一个答案依赖人工直接改余额、猜测状态或忽略未知交易类型，该部分就没有完成。

### 1.3 为什么不能只看 Stripe Dashboard

Dashboard 可作为外部运营证据，但不包含本系统的业务订单幂等、合作方分润规则、履约状态、内部权限与恢复任务。本系统并不宣称 Stripe 记录不可靠；我们需要证明本地没有漏接、误解或重复应用这些记录，并把外部资金变化与内部义务对应起来。

## 2. 事实依据与设计约定

Stripe 事实通过本节官方文档核对，最终行为以冻结版本和实际测试账户的契约记录为准。1.1 修订补查了 Checkout 过期、metadata、幂等键、退款和 BT 文档。会计归类、分润冻结、重试节奏是本项目政策。PR00 记录候选 API/SDK 版本和账户能力矩阵；PR01 验证运行依赖，PR05 验证真实 Stripe 适配。尚未取得的账户能力、版本实测和报告资格一律标记待验证，不能把文档阅读写成联调通过。

| 官方事实（简要） | 对设计的影响 | 来源 |
|---|---|---|
| Webhook 可能重复且不保证顺序；验签需要原始请求体 | 不用到达顺序做最终事实；原始字节验签后再解析 | [S1](https://docs.stripe.com/webhooks) |
| Stripe 幂等结果会缓存，包含部分错误结果；键并非永久保留 | 本地业务唯一性必须持久化，过期后不能盲目重发资金写请求 | [S2](https://docs.stripe.com/api/idempotent_requests) |
| PaymentIntent 跟踪支付过程，可能关联多次 Charge 尝试 | 业务支付与渠道尝试分表，不能假设一个 PI 只有一个 Charge | [S3](https://docs.stripe.com/payments/payment-intents) |
| Balance Transaction 有 amount、fee、net、currency、source 和可用状态；net = amount - fee | 以外部净资金变化做 PSP 账户控制，保留毛额和费用 | [S4](https://docs.stripe.com/api/balance_transactions/object) |
| 退款可能部分、待处理或失败；原支付处理费不能假设随退款退回 | 退款状态与资金分录独立，实际费用取证据 | [S5](https://docs.stripe.com/refunds) |
| 争议可能先扣款、胜诉返还；败诉时通常不再发生一次相同扣款 | 资金移动与争议结论分开，防止重复记账 | [S6](https://docs.stripe.com/disputes/how-disputes-work) |
| Payout 有原始及失败返还的 Balance Transaction 关联 | 出款和返还各记一笔外部财务事实，不能靠状态翻转改旧账 | [S7](https://docs.stripe.com/api/payouts/object) |
| 按 payout 查询所含交易用于自动 payout，不能任意套用到手动 payout | 自动批次核对和手动余额核对分别实现 | [S8](https://docs.stripe.com/api/balance_transactions/list) |
| Balance API 返回当前余额；日终报告可提供期间余额信息 | 当前快照不能冒充历史日终快照 | [S9](https://docs.stripe.com/api/balance)、[S10](https://docs.stripe.com/reports/balance) |
| Stripe 测试环境不移动真实资金，不适合用作高负载压测目标 | 契约联调与本地负载/故障实验分别报告 | [S11](https://docs.stripe.com/testing) |

术语：PSP = Payment Service Provider；BT = Balance Transaction；PI = PaymentIntent；Journal = 一张已过账凭证及其分录；Normalized Financial Event = 具有稳定身份的内部规范化财务事件。

## 3. 用户、业务模型与范围

### 3.1 业务故事

一个数字商品平台向客户销售内容，Stripe 收款。平台可给内容合作方计提收入分成。财务每天核对 Stripe 余额变动与平台账本，并追踪 Stripe 向平台银行账户的出款。客服根据已验证支付状态决定是否履约和退款，运营处理缺单、争议与对账差异。

本期分润仅为内部计提/撤回，不向合作方真实转账。平台银行收到 Stripe payout，不会自动结清合作方应付账款。这一限定让“分润上限”和“payout 流程”各自语义清晰。

### 3.2 角色与场景

| 角色 | 要完成的任务 | 权限边界 |
|---|---|---|
| 业务应用 | 创建/查询支付、申请退款、接收支付结果 | 绑定组织和应用，不可任意改金额或 Stripe 账户 |
| 客户 | 完成受托管的支付流程、必要认证、查看结果 | 无账务或运营权限，不接触服务端密钥 |
| 客服 | 查看订单时间线、申请退款 | 有限金额与操作权限，不改凭证 |
| 财务 | 查看试算平衡、余额差异、payout 到账、分润调整 | 可发起有证据的调整建议，不能直接修改旧分录 |
| 复核员 | 审批人工调整和疑难匹配 | 不能审批自己创建的调整 |
| 运维 | 观察任务与故障、重驱、恢复 | 不能随意把 UNKNOWN 改为成功 |
| 合作方 | 查看自身计提与撤回明细 | 只读自己的内部应付，非 Stripe 账户拥有者 |

### 3.3 必须交付

- 一次性卡支付，自动 capture；使用 Stripe 托管 Checkout 作为最小客户入口，跟踪其关联 PI/Charge。不处理原始卡号。
- 支付创建、认证状态展示、查询、取消意图、退款、争议接收与结果跟踪。
- Stripe BT 同步、复式账本、内部合作方分润、余额级对账、payout 与银行证据核对。
- Webhook 主路径与三层缺单恢复。
- 七项资金不变量、八类 CI 故障模式、属性测试与夜间试算。
- 最小运营页面、指标、日志、人工差错处理和英文展示材料。

### 3.4 明确不做

二维码、门店 POS、自有钱包、订阅计费、发票税务引擎、分期、多次 capture、FX 兑换、Stripe Connect Transfer、合作方实付、银行直连、多个 PSP 自动切换、真实生产资金运行。

单一组织拥有一个 Stripe 测试账户；模型保留组织/Stripe账户/环境/币种维度并做隔离测试，但不实现虚拟商户共享一个账户冒充 Connect。默认测试用 USD，10000 = USD 100.00；单次运行不兑换币种。JPY 可作为另一套零小数配置测试，不因赴日求职就把所有示例强制改成 JPY。

工程基线为 Java 21、Spring Boot、PostgreSQL 和 Flyway，配套 Redis、Kafka。使用模块化单体与独立 API/Worker 运行角色，共享一个 PostgreSQL 事务域。具体新仓库位置在创建工程时确定；不得在已有支付项目内新建业务模块或复用其构建、配置和迁移。

## 4. 资金事实与数据权威

### 4.1 四种记录不能相互替代

| 层次 | 内容 | 权威来源 |
|---|---|---|
| 业务意图 | 谁要买什么、金额、退款请求、分润比例 | 本地业务订单、版本化政策 |
| 提供方执行事实 | PI、Charge、Refund、Dispute、Payout 当前状态 | Stripe API 查询与验证过的通知 |
| 提供方资金事实 | BT 毛额、费用、净额、余额类型 | Stripe BT 与对应报告 |
| 银行到账事实 | 银行入账金额、币种、参考号、日期 | 导入的银行记录；演示使用明确标注的 fixture |

客户回跳和 HTTP 200 都不能代替资金事实。Stripe 的 payout.paid 作为提供方完成出款的证据，不自动等同于本系统已核对银行入账。

### 4.2 本地账本的确认基线

- 会影响 PSP 余额的外部会计分录，以已取得的 BT 为正式入账依据。PI 成功但 BT 尚未取得时：支付可确认成功，financialStatus 为 PENDING_EVIDENCE，不虚构手续费或银行资产。
- Webhook、查单、BT 扫描均进入同一个规范化处理管道。禁止 payment_intent.succeeded、charge.succeeded、BT 扫描分别为同一笔收款记三次账。
- 不把每个 Stripe Event ID 都当作一个独立会计事件。外部资金事件的规范键为（组织、Stripe账户、livemode、BT ID）。
- 内部分润、争议损失重分类、银行匹配和人工调整有独立且稳定的业务事件键。
- 原始通知事件去重与财务语义去重是两层防线。一个 Webhook 可以不记账，也可以引出多个尚未见过的 BT。
- 业务状态版本、外部对象快照、抓取时间、原始证据摘要、BT 身份与凭证必须能够追溯。
- 外部 BT 金额、fee、net 视为资金事实；pending/available 是可刷新属性，不把可用性变化误认为另一笔收款。
- 不能识别的 BT 类型不能丢弃：先进入未分类队列；确属本账户 payments balance 且金额可验证时，通过 PSP 对 Suspense 的平衡分录反映资金事实，再经审批重分类。其他余额类型单独隔离，不混入 payments balance。

## 5. 功能需求：支付网关

### FR-PAY-01 创建与幂等

通过认证的业务应用提交订单号、受控商品引用、价格版本、金额、币种及 Idempotency-Key；通知目标由服务端配置引用选择。PR02 提供少量受控价格/分润政策 fixture，PR06 校验请求金额与服务端版本一致，并创建不可变业务订单。客户端不能覆盖商品价格、渠道账户或订单金额；同订单改价必须拒绝。本期不建设完整商品、优惠和税务系统。支付创建持久化订单、支付、请求指纹、PSP 操作及执行义务后，才调用 Stripe。

- 同范围同键同内容返回同一支付资源；同键不同语义内容返回 409。订单业务唯一键与 HTTP 请求幂等键是两项约束：更换请求键也不能为同一订单创建第二笔业务支付。对同一已存在订单创建新的幂等键映射时仍返回原资源；与原意图冲突则拒绝。
- 本地唯一键不随 Redis 缓存过期失效。Stripe 写请求使用持久化的操作级幂等键，create/confirm/refund 不能互相复用同一键。
- 调用在数据库事务外执行。崩溃后能找回原操作，不通过新建 PI 回避未知结果。
- Stripe 键超过已验证保留窗口且无外部 ID 时，先通过持久关联和外部扫描调查；无法排除已受理则保持 UNKNOWN 并升级，不盲目重新创建。
- 真实 StripeAdapter 在 PR05 实现，FakeProvider 只用于本地实验。记录 Checkout Session ID、PI ID、成功/失败 Charge 尝试和 BT 引用。创建 Session 的响应只表示托管会话创建，不表示已扣款；允许初始 PI 关联尚不存在，后续查询/事件补齐。
- Checkout 与 PI 的 metadata 分别按契约配置；需要传到 PI 的信息通过 payment_intent_data.metadata 明确设置，同时保留本地可信订单映射。不能假设顶层 metadata 自动传播。[Stripe metadata](https://docs.stripe.com/metadata)
- 交易成功金额使用最终 capture 事实，不拿最初请求金额充当已收款金额。

### FR-PAY-02 客户动作与状态

展示待支付方式、待认证、处理中、结果确认中、成功、取消。需要客户动作时给出 Stripe 托管入口，不让 UNKNOWN 界面诱导重复支付。

“卡被拒绝”首先是一次尝试失败；PI 若仍允许重试支付方式，不将整个订单不可逆标为失败。保存提供方原状态，并映射本地业务状态和最近确认时间。成功后可有退款或争议，不能把原支付事实抹掉。

### FR-PAY-03 查询与取消

查询返回 paymentStatus、providerStatus、resolutionStatus、captured/refunded/reserved、financialStatus、nextAction、最近确认时间。取消前区分未发出请求、等待客户、处理中及已成功。Checkout 路径优先按实际会话状态使用适配器的过期能力；open Session 才可按官方接口过期，其他结果重新取证处理。不能将通用 cancelPayment 接口机械映射成任意 PI 取消。提出取消、客户回跳或本地超时均不能直接推断未付款；会话过期证据也要与已有支付/资金事实核对。[Checkout 过期接口](https://docs.stripe.com/api/checkout/sessions/expire)

不预设所有提供方对象状态只单向变化；遇到矛盾证据按最新权威查询和资金记录调查，保存审计，不用“后到消息覆盖前面消息”处理。

### FR-PAY-04 履约通知

支付/退款业务状态确认与 Outbox 同事务提交。通知 Worker 独立重试；eventId 稳定，商户端以业务键幂等履约。商户应在业务处理已持久化后返回 2xx。模拟商户在自己的数据库中以稳定业务键去重，并与履约效果同事务提交，证明该配合条件下只产生一次对应成功效果。对于任意第三方系统，只能承诺通知可重试和身份稳定，不能替对方保证 exactly-once。

回补历史缺单时，是否自动通知履约取决于是否存在仍有效的原业务订单；只有“恢复记录”不意味着应该再次发货。

## 6. 功能需求：退款与分润

### FR-REF-01 原子预占

设：
- C：确认的累计 capture 金额，本期一笔支付仅一次成功 capture。
- R：成功退款累计金额。
- H：本地已批准但尚未明确失败/成功的退款预占。
- 所有数值为同币种最小单位整数。

NORMAL 状态下 C、R、H 均非负，必须满足 0 <= R + H <= C。申请退款金额必须为正；在同一短事务锁定支付控制行，检查状态、争议限制与金额，增加 H，写退款单、操作意图，并按 FR-SHARE-01 调整分润；提交后才能调用 Stripe。

成功时 H 转 R；明确失败/取消时释放 H；超时、pending、requires_action 或未知时保留 H。释放和完成均条件更新且幂等。支付与退款复用 provider_operation、租约和恢复框架；PR11 必须接入退款查单、未知结果重领、超时升级和通知，不能只添加退款状态表。资金影响按 BT 入账，不能因为本地预占就减少 PSP 资产。

平台外部通过 Stripe Dashboard 发起的退款也需摄入。该事实优先于本地意图，冻结冲突操作并查明已有预占是否对应同一退款。退款控制行标为 EXTERNAL_CONFLICT 时停止新退款，不丢弃外部事实以强行维持乐观本地状态；解决映射后才恢复 NORMAL。

### FR-REF-02 金额与费用

支持部分/全额退款，不允许总额超过 capture。退款状态和退款 BT 到达可能不同步，使用双维度展示。退款失败若产生返还 BT，必须作为新事实反向入账。原支付费用不自动退回，只有证据支持的费用返还才记回；禁止沿用旧 PRD“按退款比例退手续费”的政策。[S5](https://docs.stripe.com/refunds)

### FR-SHARE-01 内部收入分成（与简历 revenue-share ceiling 对齐）

业务动机：平台承诺给合作方的金额，必须随有效销售收入变化；否则客户退了一部分钱，合作方却仍按原收入获得全部计提。

这是内部计提模型，不是 Stripe Connect，不代表合作方真实实付。每个支付可配置多个合作方比例，比例以基点表示并在下单时快照，合计 <= 10000。

对于没有阻断争议且已具备收款记账证据的订单：
- eligibleBase = max(0, C - R - H)。
- 各合作方 cap_i = floor(eligibleBase * rateBps_i / 10000)。
- 有效计提 S_i = 历史增加计提 - 历史撤回计提，要求 0 <= S_i <= cap_i。
- 采用“向下取整，各合作方独立计算；余数归平台”的明确政策。
- 在申请退款增加 H 的同一事务中，将超出新上限的计提撤回；退款失败释放 H 后，按同一政策幂等恢复计提。
- 成功退款 H 转 R 不重复减少上限；政策不能按“每次退款独立取整后相加”计算，以免碎片化退款产生舍入套利。
- 存在未决争议、已败诉争议或未解决资金矛盾时，整笔订单 eligibleBase = 0，撤回全部尚未实付的计提。这是保守业务政策，并不假设争议金额等于剩余未退款金额。
- 胜诉且相关资金/账务证据已完整、没有其他阻断争议后，可重新计提。部分退款后发生争议也遵守同样政策，不用 R + 争议金额简单相加。
- 首次计提、撤回和恢复分别使用稳定内部财务事件键，追加 PARTNER_SHARE_EXPENSE/PARTNER_PAYABLE 凭证，并与 allocation 投影同事务提交；不能只修改 cap 或余额字段。事件身份包含支付、合作方、政策版本和持久化业务触发版本，重复调用不得换随机键。合作方实付及付款后追偿不在本期。

反例：C=10000、比例20%、已有计提2000；预占退款4000后 cap=1200，必须撤回800。若仍保留2000，即为应被属性测试发现的上限缺陷。本反例是设计的测试案例，不是已经发现的真实缺陷。

### 6.1 分阶段启用要求

PR06 即保存下单时政策快照。PR10 建立收款记账证据，PR11 完成退款，PR12 才启用分润并把全部退款路径接入原子调整；PR12 对已具备 BT 的历史演示订单执行幂等计提回补。PR13 再接入争议冻结、退款阻断及胜诉恢复。前置模块未完成时不能对外宣称分润/争议闭环已交付。

## 7. 功能需求：争议

### FR-DIS-01 生命周期与资金移动分离

保存 disputeId、chargeId、原因、金额、币种、提供方状态、截止时间和证据关联。运营可查看和标记跟进；证据文件提交和实际抗辩自动化不在基线，使用 Stripe Dashboard/测试流程完成。

- inquiry/warning 不自动记资金扣减；只有真实 BT 触发 PSP 资产变动。
- 正式争议扣款时，争议本金暂记争议应收，实际费用独立记费用。
- 胜诉返还按新 BT 记回 PSP；败诉按未追回争议应收转损失，不再次扣 PSP。
- 返回资金先到、状态后到，或 lost 后 late win，均以不可变调整完成，不修改旧账。
- 不假设争议金额 <= 原支付额、不假设一个 Charge 只会有一项争议；异常金额以证据保存并进入人工解释流程。
- 开放争议期间阻止本系统新发起普通退款；对外部已发生退款与争议重叠保留事实并调查。
- 分润冻结/撤回按照 FR-SHARE-01 执行，不把争议状态当作另一次退款。
- 争议费和费用返还使用实际 BT/fee_details，不能硬编码“胜诉退回所有费用”。

官方资金生命周期依据：[S6](https://docs.stripe.com/disputes/how-disputes-work)。Dispute 对象关联字段依据：[S12](https://docs.stripe.com/api/disputes/object)。会计归类为本项目的演示政策。

## 8. 功能需求：PSP 资产与 payout

### FR-PO-01 定义与边界

Payout = Stripe 把平台 Stripe 账户中的钱转到平台绑定的银行账户。它不是给合作方分润，不是收款 capture，也不是客户退款。

先实现摄入与查询 payout；测试账户支持时增加受限测试 payout 发起。自动和手动 payout 都要记录，但只有提供方明确给出归属时才做交易到 payout 的映射。不能用 FIFO 猜出手动 payout 的“官方包含订单”。[S7](https://docs.stripe.com/api/payouts/object)、[S8](https://docs.stripe.com/api/balance_transactions/list)

### FR-PO-02 出款与银行证据

- payout 关联的扣减 BT 出现时：借银行出款在途资产、贷 PSP 资产；实际出款费独立记账。
- payout.paid 只更新提供方状态，不直接借记 BANK_CASH。
- 银行记录核对到账后：借 BANK_CASH、贷银行出款在途；不影响收入。
- payout 失败/取消导致余额返还时，按 failure_balance_transaction 入账，不用本地手工把历史扣减删除。
- 已有银行匹配后又出现返还/失败证据时，标记冲突并使用银行退回证据和追加调整处理，不重复冲回银行现金。
- 状态 UNKNOWN 或到账逾期期间保留在途余额，不能自动再出款。
- 演示银行文件使用固定 schema 与 fixture；页面、报告及简历说明是 simulated bank evidence，不能宣称真实银行直连。

### FR-PO-03 少结算和费用差异

“payout 小于毛收款”不自动是少结算。先扣除真实退款、费用、争议、其他调整、不可用余额和留存，再解释净额差异。

对已匹配 payout：
- 预期净银行到账 = payout 本金 - 已被证据确认且未重复入账的银行侧费用。
- 只有币种、参考号、时间窗和完整银行证据可确认后，才把实收不足标成 SHORT_RECEIPT。
- 未到银行、银行证据缺失、pending余额尚未可出款属于 WAITING_EVIDENCE，不宣称 Stripe 少打款。
- 手续费异常用“实际费用”与“版本化费率预期”比较；预期模型不完整时标为 UNMODELED_FEE，不使用猜测覆盖实际分录。

## 9. 复式账本设计需求

### 9.1 账户表

以下是平台作为销售主体的简化演示账务政策，不是通用财务报表标准。所有账户有组织、Stripe账户归属（适用时）、环境、币种和用途。

| 账户 | 类别 | 含义 |
|---|---|---|
| PSP_FUNDS | 资产控制账户 | Stripe payments balance 中尚未转出的钱；pending/available 用子维度解释 |
| BANK_PAYOUT_IN_TRANSIT | 资产 | Stripe 已扣出但尚未核对银行收到的金额 |
| BANK_CASH | 资产 | 有银行证据确认的现金；测试环境为模拟 |
| DISPUTE_RECEIVABLE | 资产 | 争议扣款后等待胜诉返还的本金 |
| SALES_REVENUE | 收入 | 证据支持的毛收款确认 |
| SALES_RETURNS | 收入抵减 | 实际退款本金；不会自动抵减原支付手续费 |
| PSP_FEE_EXPENSE | 费用 | 实际 PSP 费用 |
| DISPUTE_FEE_EXPENSE / DISPUTE_LOSS | 费用 | 争议费用、败诉后本金损失 |
| PARTNER_SHARE_EXPENSE | 费用 | 内部分润计提 |
| PARTNER_PAYABLE | 负债 | 尚未对合作方实付的计提净额 |
| BANK_FEE_EXPENSE | 费用 | 有银行证据的银行侧费用 |
| SUSPENSE | 待分类账户 | 已知资金变化但尚未完成业务归类 |
| OPENING_EQUITY | 初始余额对应项 | 明确证据支持的迁入期初，禁止用来掩盖差异 |

PSP 控制账户可以出现负余额事实，不能设置一个“资产永不为负”的错误全局约束来拒绝 Stripe 真实扣款。负余额产生告警并受控处理。合作方计提在本期禁止负数。

### 9.2 示例分录

下表金额仅为演示输入，不代表 Stripe 实际费率；各行按对应财务事实发生，非假设所有事件必在同一交易出现。

| 财务事实 | 借方 | 贷方 |
|---|---|---|
| Charge BT：amount=10000、fee=300、net=9700 | PSP_FUNDS 9700；PSP_FEE_EXPENSE 300 | SALES_REVENUE 10000 |
| 20%合作方计提 | PARTNER_SHARE_EXPENSE 2000 | PARTNER_PAYABLE 2000 |
| 退款预占4000导致分润撤回 | PARTNER_PAYABLE 800 | PARTNER_SHARE_EXPENSE 800 |
| Refund BT：amount=-4000、fee=0、net=-4000 | SALES_RETURNS 4000 | PSP_FUNDS 4000 |
| 争议本金3000、实际费用1500被扣 | DISPUTE_RECEIVABLE 3000；DISPUTE_FEE_EXPENSE 1500 | PSP_FUNDS 4500 |
| 争议胜诉本金返还3000 | PSP_FUNDS 3000 | DISPUTE_RECEIVABLE 3000 |
| 争议败诉且本金仍在应收3000 | DISPUTE_LOSS 3000 | DISPUTE_RECEIVABLE 3000 |
| 败诉后late win返还3000 | PSP_FUNDS 3000 | DISPUTE_LOSS 3000（关联原损失） |
| payout本金5700、无附加费的扣减BT | BANK_PAYOUT_IN_TRANSIT 5700 | PSP_FUNDS 5700 |
| 银行到账5700 | BANK_CASH 5700 | BANK_PAYOUT_IN_TRANSIT 5700 |
| 未银行到账前payout失败返还5700 | PSP_FUNDS 5700 | BANK_PAYOUT_IN_TRANSIT 5700 |

争议胜诉、败诉是互斥分支，late win 为追加分支。争议与退款重叠、部分返还、独立费用 BT 必须由规则表分类，不能照抄单一示例金额。恢复款按“尚存应收优先、已确认损失其次”的关联规则拆分，超出可解释金额进入 Suspense。

### 9.3 入账原子性与不可变性

- 一个已接受的规范化财务事件与一张平衡 Journal 在同一 PostgreSQL 事务提交；暂缺证据的 observation 不算已接受财务事件。
- Journal 至少两条有效 Entry，金额使用最小单位整数，借贷方向明确；零额观察不制造零额分录。
- Journal、Entry 封存过账后不可更新/删除，也不得向已完成 Journal 追加新 Entry，即使补入的分录仍借贷平衡。修正创建关联的 reversal/adjustment，唯一键不能通过换随机 ID 绕过。
- 外部 BT 同键不同金额/fee/net 出现时不是普通 duplicate，必须触发证据冲突。
- 唯一业务键、账户归属、分录类型、余额控制和聚合约束在数据库和唯一入账服务实现；不能只依赖 Java assert。
- PR03 提供受控的一次性入账过程；构建分录集合、校验和封存发生在同一事务，已提交 Journal 仅有 POSTED。结合 PostgreSQL 延迟约束触发器检查最终集合，拒绝空凭证、零额/单行和不平衡凭证。应用运行角色不得直接任意写账本；迁移角色与运行角色分离。幂等事件插入、Journal 与封存边界必须可由直接 SQL 权限/约束用例验证。
- 多行不变量需要锁定稳定父记录/控制行，以固定顺序取得锁；重试死锁或序列化失败时保持幂等。
- 余额投影与分录由唯一 LedgerPostingService 在同一事务更新，BT Importer、Webhook 和分润服务不得再次增减账户投影。重建使用明确的期初截止点与之后的不可变分录；若期初已作为 Journal 入账，则从零累计这些分录，不能再加一次期初快照。
- raw/provider observation 与 ledger posted 的状态分离，不能为了“立即可用”用估计费用强行记成正式凭证。

## 10. 七项资金不变量

恰好七项为本项目核心验证集合。它们有不同作用范围：本地不变量可提交时拒绝，外部一致性只能在证据完整且截止一致后核对，不能宣称提交事务即可保证 Stripe 与银行一致。

| ID | 精确定义 | 强制/检测时点 | 失败行为 |
|---|---|---|---|
| I-01 借贷平衡 | 每张已过账凭证同币种 sum(debit)=sum(credit)，至少两条有效分录 | 提交时；夜间试算；属性测试 | 回滚入账、报警 |
| I-02 事件唯一入账 | 每个已接受规范化财务事件恰好一个 Journal；重复观察不能多记，冲正是关联的新事件 | 数据库唯一键及同事务提交；事件覆盖扫描 | 重复返回已有结果；冲突隔离 |
| I-03 退款额度 | NORMAL 控制行中 0<=R+H<=C；所有本系统新发起退款先预占 | 支付父行锁/条件更新；每次完成/释放；夜间 | 拒绝超额；外部并发冲突冻结并调查 |
| I-04 分润上限 | 每合作方 0<=S_i<=floor(eligibleBase*rate_i/10000)，各比例合计<=10000 | 退款预占/争议/计提同事务；夜间；属性测试 | 撤回或拒绝超额计提，禁止旧账修改 |
| I-05 PSP资金守恒 | 同账户/环境/币种/余额域/截止点：本地PSP余额=期初+完整BT净变动；并与独立Stripe期末证据一致 | BT逐笔投影；日终对账；属性模型 | 保留差异，不制造平账分录 |
| I-06 出款守恒 | 单payout已从PSP转出的本金=尚在途本金+已确认银行到账本金+已确认返还PSP本金（含经验证调整）；费用单列 | 本地分配提交时；银行/PSP证据齐备后外部核对 | 超额匹配拒绝；缺证据暂挂；短款差错 |
| I-07 隔离与可重建 | 一张凭证内组织、环境、币种一致；账户余额投影=期初+对应不可变分录；同一资金来源不能跨域重复归属 | FK/约束/事务；独立重建；属性测试 | 回滚跨域写；投影差异告警修复 |

I-02 的唯一性可由提交约束保证，但“所有已接受事件都已覆盖”还要扫描；I-07 的归属可提交拒绝，但余额重建是独立检查。I-06 中本地匹配额度可事务内约束，外部本金守恒仍需完整证据，不能将整项简单归为仅本地或仅外部。

I-05 不能只比较“同一份导入文件求和”和“由同一文件生成的账本”：必须有完整性游标、外部报告/快照、覆盖范围和未分类项检查。I-06 中银行到账与返还的重叠证据需要拆分冲突，不能把两者都算满额。

### 10.1 夜间检查

每个启用账户按配置业务时区执行试算、账户余额重建核对、退款预占老化、分润上限、BT覆盖、PSP余额和payout在途检查。输出 PASS/FAIL/INCOMPLETE，不将缺证据视为 PASS。报告固定输入高水位、运行 ID、规则版本和残差明细。

## 11. 三层 silent payment loss 防护

### 11.1 什么是“静默丢单”

Stripe 已有成功收款事实，但本地记录仍未知、未成功或完全缺失，并且没有有效告警或恢复任务。本系统能恢复交易记录，不承诺仅凭状态就恢复货物履约或实际丢失资金。

| 层次 | 发现范围 | 默认策略（本项目设置） | 无法覆盖 |
|---|---|---|---|
| L1 延迟轮询 | 已知刚提交的操作与外部ID | 5/15/30/60/120秒受控查询，抖动与账户级限流 | 整条本地订单与操作记录丢失 |
| L2 定时状态扫描 | 本地UNKNOWN、过期租约、缺BT及长时间处理中 | 每5分钟扫描，到期任务重领，年龄阈值报警 | 无本地锚点的外部成功支付 |
| L3 T+1财务对账 | Stripe侧枚举的BT与来源对象，包括本地不存在的支付 | 每日抓取T业务日数据，重叠回看与手动回补 | 提供方尚未提供证据或超出可查询范围 |

T+1 表示次日运行的发现与核对任务，不是宣称 Stripe 次日结算，也不是所有异常最多一天必恢复。默认运营时区 UTC，可配置 Asia/Tokyo；使用时区生成明确 UTC 区间，不用固定减24小时计算所有日期边界。

### FR-REC-01 BT导入与完整性

- Stripe账户/环境隔离，按固定created时间窗口分页，页面及游标持久化，崩溃后安全继续。
- 全部分页完成且证据版本固定后才能标记本窗口 COMPLETE；不能只处理第一页或提前推进最终水位。
- 默认回看最近7个业务日并对pending资金继续跟踪；支持任意历史范围手动backfill。7天是本项目补偿策略，不是Stripe数据保留承诺。
- 晚到或重分类证据触发受影响日期重跑。保存费用/净额原始数据及摘要，重复BT不重复记账。
- 未支持类型、FX、其他balance_type进入明确的未覆盖清单；窗口不能因“代码不认识”静默漏掉金额。
- 控制账户从空测试账户启动；已有账户迁入必须提供核对过的期初快照和截止区间，禁止全历史BT再加全额期初双计。

### FR-REC-02 安全自动回补

BT -> source Charge -> PI -> 持久业务订单/可信metadata映射，取回权威金额、币种、账户与状态。

仅在以下条件同时满足时自动恢复：
1. 账户、livemode和币种在授权范围。
2. 来源对象确认是成功capture的收款，而非仅授权、退款或余额调整。
3. 业务订单号映射唯一，金额币种符合原业务意图，没有现存冲突。
4. 来源归属由服务端创建约定和本地业务证据验证，不能只信任任意metadata字符串。

恢复记录带 origin=RECONCILIATION、来源BT、运行ID、证据和恢复时间，走同一去重/入账管道。本地缺支付记录但仍有业务订单时可自动恢复关联；连业务订单也缺失时只恢复隔离的财务观察/未分配记录，禁止虚构客户订单或自动履约。金额不符或归属歧义进入人工差错。

### FR-REC-03 三个层次的对账

| 层次 | 比较内容 | 差异 |
|---|---|---|
| 交易级 | PI/Charge/Refund/Dispute等外部事实与本地资源 | 缺单、重复关联、状态/金额/币种不符 |
| 余额级 | PSP期初 + BT净变动、账本控制余额、独立期末报告/快照 | 缺BT、费用漏记、漏扣退款、漏记争议、未知调整 |
| Payout/银行级 | payout本金、自动批次归属（可用时）、返还BT与银行记录 | 未到账、短款、费用差异、重复匹配 |

实时Balance API快照不能直接与昨日账本截止点比较。能获得对应期间报告时使用报告；否则在可控静止区间采集快照，并将缺历史期末证据的运行标为 INCOMPLETE。测试用独立生成的提供方账本/银行fixture建立可验证oracle。pending转available仅改变可用性维度，不改变PSP总额。

自动payout的交易归属与窗口包含的payout自身扣减必须分别计算，避免把“所含交易净额”和“payout负BT”在同一批次汇总中重复抵扣。

## 12. 差错处理与最小产品页面

差错类型包括 MISSING_PAYMENT、UNKNOWN_SOURCE、STATUS_CONFLICT、AMOUNT_MISMATCH、FEE_VARIANCE、BALANCE_RESIDUAL、PAYOUT_WAITING、SHORT_RECEIPT、DUPLICATE_BANK_MATCH、UNSUPPORTED_BALANCE_DOMAIN。

工单状态：OPEN -> INVESTIGATING -> RESOLVED；缺证据可 WAITING_EVIDENCE；重跑发现问题复发可 REOPENED。解决必须关联查询证据、补录命令或经复核的调整凭证，不允许“标记已解决”就清零差额。

| 页面 | 必须回答/操作 |
|---|---|
| 支付详情 | 外部对象、请求与尝试、状态、退款额度、分润、财务同步、时间线 |
| 退款页 | 申请金额、预占、Stripe状态、BT影响与失败返还 |
| 争议页 | 原支付、金额、截止时间、扣款/返还/损失、分润冻结原因 |
| Ledger Explorer | 按业务号/BT/event查Journal与Entry，借贷总额、冲正关联 |
| 余额对账页 | 期初、各类毛额/费/净额、期末、残差、数据水位与完整性 |
| payout页 | Stripe状态、扣减BT、银行证据、在途、返还和差额 |
| 分润页 | 比例快照、当前eligibleBase/cap/计提/撤回及原因 |
| 差错与任务页 | 证据、租约、重试、负责人、审批和审计 |

页面英文优先，文档中文详细解释并提供英文工程材料；不建设大型营销站点和通用 ERP 后台。

## 13. 建议状态模型

本表为本地拟定模型，providerStatus 保存Stripe原值；UNKNOWN属于结果确定性，不能覆盖最后已知provider事实。

| 对象 | 维度/主要状态 | 关键规则 |
|---|---|---|
| Payment | CREATED/AWAITING_CUSTOMER/REQUIRES_ACTION/PROCESSING/SUCCEEDED/CANCELED；resolution=KNOWN/UNKNOWN/CONFLICT | 一次尝试被拒绝不必终结整个Payment；退款/争议不抹掉成功capture |
| ProviderOperation | PREPARED/SENDING/ACKNOWLEDGED/UNKNOWN/RESOLVED/ESCALATED | 支付和退款共用；SENDING 在崩溃后不证明请求未发出；过期未知不盲重发 |
| Refund | RESERVED/SUBMITTED/PENDING/REQUIRES_ACTION/SUCCEEDED/FAILED/CANCELED；resolution单列 | 明确结束前保留额度；外部BT可与状态暂时不同步 |
| Dispute | providerStatus原样；localHandling=NEEDS_REVIEW/WAITING_EVIDENCE/RESOLVED | 不把lost永久禁止为won；资金以BT+重分类事件处理 |
| Payout | providerStatus=pending/in_transit/paid/failed/canceled；bankMatch=UNMATCHED/PARTIAL/MATCHED/CONFLICT | paid不代表银行已匹配；失败返还有独立BT |
| FinancialObservation | RECEIVED/PENDING_EVIDENCE/CLASSIFIED/QUARANTINED | 不足证据不冒充已入账财务事件 |
| Journal | POSTED | 修改使用新Journal，草稿只在构建/观察层 |
| ReconciliationRun | CREATED/FETCHING/VALIDATING/COMPLETED/INCOMPLETE/FAILED | 完成抓取不等于无差异，另有matched/unresolved计数 |

## 14. 接口、数据与执行边界

### 14.1 接口与合同交付

PR00 的 OpenAPI 至少完整定义首批支付/退款/回调接口的必填字段、响应、认证、状态和错误示例，不能只有路径占位。后续运营接口在所属 PR 前补齐并与页面逐项对应。

| API | 用途与实现归属 |
|---|---|
| POST /v1/payments | PR06 幂等创建业务订单/支付/Checkout 操作；返回 resource、nextAction/托管URL或结果待确认 |
| GET /v1/payments/{id} | PR06 基础查询，PR11/12/13补退款/分润/争议维度；只按可信身份读取 |
| POST /v1/payments/{id}/cancel | PR06提交取消意图；PR07/08查询与事件收敛 |
| POST /v1/refunds | PR11 同事务预占、操作/执行任务与通知义务；网络调用在外 |
| GET /v1/refunds/{id} | PR11 状态、预占、恢复进度及资金证据 |
| POST /webhooks/stripe | PR08 原始字节验签、持久Inbox后2xx；持久化失败返回可重试错误 |
| GET /ops/payments/{id}、/ops/refunds/{id} | PR16 运营详情与关联时间线 |
| GET /ops/disputes/{id}、/ops/payouts/{id}、/ops/revenue-shares | PR16 争议、出款、合作方计提及证据查询 |
| GET /ops/ledger/journals、/ops/ledger/journals/{id}、/ops/balances | PR16 凭证检索、分录/冲正关联与余额维度 |
| POST /ops/reconciliation-runs、GET /ops/reconciliation-runs/{id} | PR16 指定日期/账户回补与进度，不能返回HTTP成功就当资金匹配 |
| POST /ops/bank-statements/import | PR16 导入明确标注为模拟的银行fixture，支持重复导入校验 |
| GET /ops/exceptions、/ops/tasks | PR16 差错、恢复任务与租约/重试状态 |
| POST /ops/exceptions/{id}/actions、/ops/tasks/{id}/retry | PR16 有权限、理由、幂等和证据的调查/重驱 |
| POST /ops/adjustments、POST /ops/adjustments/{id}/approve | PR16 调整申请与独立审批，不能缺少“创建申请”的入口 |

业务应用身份绑定组织、环境和应用；币种/Provider账户从授权配置解析。缺少或伪造 TenantContext 拒绝，不能信任请求中的 organization_id。运营身份独立授权；合作方只看自身记录。稳定错误至少包括 IDEMPOTENCY_CONFLICT、ORDER_INTENT_CONFLICT、AMOUNT_MISMATCH、UNAUTHORIZED、FORBIDDEN、REFUND_LIMIT_EXCEEDED、REFUND_BLOCKED、EVIDENCE_CONFLICT；具体 HTTP/code/retryable/requestId 在 OpenAPI 固定。UNKNOWN 是资源结果确定性，不是把所有网络错误统一为业务失败。

### 14.2 范围、身份和实体

| 范围 | 定义与约束 |
|---|---|
| TenantScope | organization_id + environment_id；由认证解析，不由客户端自由指定 |
| ProviderScope | TenantScope + provider_account_id + livemode；账户登记唯一归属，复合FK约束组织/环境一致 |
| MoneyScope | TenantScope + currency；每张Journal内一致，整数金额禁止跨币种相加 |
| PSPControlScope | ProviderScope + currency + balance_type；payments域与其他域分开，核对再加固定截止点 |

外部对象唯一键带 ProviderScope 和对象类型/对象ID；规范BT财务键为组织、Provider账户、环境/livemode和BT ID，币种是必须校验的事实属性，不能改币种后绕过同BT冲突检查。Webhook去重为ProviderScope+eventId。一个外部资金源不能注册到多个组织重复归属。业务订单唯一键为组织、环境、应用、order_key；API幂等记录还区分操作类型。内部业务财务键包含可信资源和触发版本。

| 实体 | 关键字段/约束与归属 |
|---|---|
| organization/application/provider_account/environment/currency | PR02认证配置、账户绑定、环境/币种和受控价格/政策fixture |
| business_order/payment/payment_attempt | PR06订单意图不可变、政策快照、Session/PI/Charge关联，多attempt不等于多次成功capture |
| provider_operation/operation_task | PR06通用写操作、稳定key、请求指纹、外部ID、持久执行义务；PR07加租约与结果证据 |
| recovery_task | PR07仅创建一次，PR11注册退款类型，PR15扩展财务发现；不得重复建同名恢复表 |
| refund_control/refund | PR11 C/R/H与controlState；每笔本地退款先有预占和通用操作关联 |
| share_policy/share_allocation | PR06订单保存政策快照，PR12实现分润实体与不可变计提/撤回凭证 |
| webhook_inbox/normalized_event/dependency_wait | PR08账户范围、原文摘要、处理状态、规范事件和依赖重试 |
| provider_observation/balance_transaction/import_cursor | PR10原始证据版本、amount/fee/net/source、分页持久进度；数据冲突不覆盖 |
| financial_event/journal/journal_entry/balance_projection | PR03稳定财务键、event与Journal一对一、账户范围FK、封存、唯一投影写入口 |
| dispute/dispute_fact | PR13状态、charge引用、扣款/返还BT及内部损失重分类 |
| payout/payout_fact/bank_statement/payout_bank_match | PR14来源银行行身份与分配唯一/累计限额、在途/返还冲突 |
| reconciliation_run/cursor/exception/fee_model | PR15固定窗口、证据完整性、独立期末、残差、版本化费用预期 |
| outbox/consumer_inbox/delivery_task | PR09稳定eventId、范围、租约和去重；退款接线在PR11完成 |
| adjustment_request/approval/audit_log | PR16申请、独立审批、追加凭证与审计；不存密钥/卡号 |

操作的资源关联必须有数据库约束。PR00选择 payment_id/refund_id 互斥FK（退款FK在PR11新增），或有明确FK的类型关联表；不能使用无人校验的任意字符串resource_id替代归属约束。账户用途、控制范围与稳定ID构造要写入迁移和测试，不能只存在于JSON说明中。

### 14.3 事务、锁和恢复边界

- 业务请求：幂等占位 + business_order/payment + operation + 持久执行义务同事务；下单政策快照在调用Stripe前固定。
- 退款：锁稳定支付/退款控制记录 + C/R/H变化 + 已启用分润的调整Journal/allocation + refund/operation + Outbox同事务。
- 财务事实接受：锁相关业务控制行 + financial_event + 平衡Journal/Entry + 唯一余额投影更新 + Outbox同事务；缺依赖先保存observation并重试。
- Webhook接收：验签后只持久化，不在HTTP请求内等待复杂账务。不同入口转同一规范管道。
- 消息消费者：Inbox去重与本地效果同事务，提交后ack；Kafka不能原子提交任意外部商户数据库。
- PR00固定锁序：业务控制行按ID排序后取得，账本账户控制行按稳定ID排序，然后按固定顺序处理财务事件；仅账本业务可跳过前面的业务锁，但不能拿到账本锁后再反向索取支付锁。死锁/序列化重试要重跑完整本地事务并保持幂等，不重复发起外部资金请求。
- 网络调用在事务外。PR07领取租约只做短事务，调用后用 lease_token/版本条件回写；旧Worker迟到结果先保留为证据，不越过新状态。租约排他仅保护本地领取，不能撤销已发出的远端请求。
- SENDING崩溃、响应丢失、查询暂空均可能有远端效果。支付/退款共用查询优先与受控重试策略；原key只在已验证安全窗口内重用。key可能过期、无外部ID、又不能排除受理时保持UNKNOWN并升级，禁止自动换键或盲重发。[Stripe幂等语义](https://docs.stripe.com/api/idempotent_requests)
- Redis只承担可重建缓存/限流；Kafka不担任账本。核心状态、执行义务和资金控制必须在PostgreSQL持久化。

## 15. 八类 CI 故障模式

恰好八类作为简历对应的核心矩阵，详细步骤见验收文档。

| ID | 故障 | 必须证明 |
|---|---|---|
| F-01 | Dropped webhook：有外部执行、无通知 | L1/L2恢复已知单，L3能发现无本地单 |
| F-02 | Duplicated webhook：同event及语义重复event | Inbox与规范财务键两层去重，一次业务效果 |
| F-03 | Out-of-order webhook：状态/财务依赖乱序 | 不用到达顺序覆盖事实，缺依赖可恢复 |
| F-04 | Tampered webhook：内容/签名被篡改 | 原始字节验签失败，无业务与财务副作用 |
| F-05 | PSP timeout：发送前/受理后响应丢失 | 保持UNKNOWN，不误判失败或换键重复扣款 |
| F-06 | Process crash：事务提交前后、处理确认前后 | 已提交义务可恢复，未提交无半凭证 |
| F-07 | Broker outage/redelivery：消息中断和重复交付 | Outbox积压可恢复，消费者幂等 |
| F-08 | Concurrent commands：双退款、退款与分润竞争 | 数据库保护额度和分润，不靠单进程锁 |

CI默认运行可控Stripe模拟器/记录契约fixture；Stripe测试环境承担少量真实契约测试，不能把伪造签名的本地模拟说成Stripe实测。

## 16. 属性测试要求

- 核心模型与并发用例随PR03、PR06、PR11、PR12等模块交付；PR18扩展跨模块组合，不能等最后才验证资金约束。使用Java属性测试工具（候选jqwik），独立纯模型计算C/R/H、计提、PSP变化、在途与凭证，不调用生产计算函数充当期望值。
- 生成支付成功、拒绝、未知、退款预占/成功/失败、争议扣款/胜诉/败诉/late win、分润调整、payout/返还/银行匹配、重复乱序及崩溃重试的操作序列。
- 每次本地事务后检查可立即成立的I-01/02/03/04/07；外部事实收敛后检查I-05/06及端到端结果。
- 既生成合法序列，也生成应拒绝的非法命令；拒绝不能有残留副作用。
- PR套件默认100个种子、每序列最多100步；夜间默认1000个种子、最多300步；按CI预算校准并记录实际数量。
- 保存失败seed、原序列、shrink最小反例、修复提交和回归用例。测试发现真实实现缺陷时允许最小生产修复或独立修复PR；禁止修改已冻结业务规则去迎合测试结果。并发验证单独用真实PostgreSQL和屏障执行，不能用串行随机序列声称覆盖所有并发。
- “发现3个缺陷”不是需求指标。若通过故意注入错误实现测试敏感性，记录为mutation，不算真实缺陷。
- 固定的部分退款分润案例只是回归基线；随机发现的缺陷必须有实际实验报告。

## 17. 可观测性、性能与安全边界

### 17.1 运营指标

unknown_payment_count/oldest_age、refund_reserved_age、webhook_lag、outbox_oldest_age、ledger_pending_evidence、unclassified_bt_count、trial_balance_failures、reconciliation_residual、payout_unmatched_age、share_cap_violations。

日志与trace关联businessOrderId/paymentId/operationId/stripeRequestId/piId/chargeId/btId/eventId/journalId/runId。request/client secret、API key、银行卡数据和敏感客户字段不进入日志。

### 17.2 实验目标，不是当前成绩

- 两个API和两个Worker实例，固定JVM、PostgreSQL、Kafka版本，保存硬件资源、数据规模和提交SHA。
- 本地模拟提供方下100 RPS写受理+200 RPS查询、100万历史支付、预热5分钟、运行30分钟；目标本地受理P95<=300ms、P99<=1s，非注入错误<0.1%。
- 不把受理TPS当真实支付完成TPS，不向Stripe沙箱发送负载测试。
- 默认执行租约30秒，到期任务领取循环默认最多间隔5秒；L2每5分钟扫描用于发现遗漏与老化，不能作为唯一重领机制。本地依赖恢复、无账户限流且队列在基线负载下，目标为崩溃后120秒内重新领取任务。外部争议结果等不承诺120秒完成。
- 正确性门槛：零重复模拟扣款效果、零本地发起超额退款、零重复财务凭证、零不平衡凭证；残差必须解释，不能仅平均掉。
- 数据库备份恢复后先做Stripe范围重扫和账务覆盖检查，再恢复写操作；过旧幂等键不直接重发。

### 17.3 安全与权限

本期运行模式严格限定Fake或Stripe测试环境，启动时拒绝live凭据/账户；使用托管收银入口，后端不收卡号。运营写操作有RBAC、审计、敏感调整双人复核；回调目标限制内部地址访问。数据库应用角色不得任意删除账本；环境变量和密钥不进版本库。未知类型和无法匹配数据保留最小必要证据，不无限制收集PII。

## 18. 验收版本与实施顺序

| 版本 | 产品结果 | 对应PR与退出条件 |
|---|---|---|
| R0 语义与工程基线 | 规范、OpenAPI、范围、账本与Provider契约 | PR00～PR05；工程从空环境可启动，账本基本约束通过，真实Stripe必需能力完成契约验证；账户阻塞明确记录 |
| R1 可靠交易及收退款资金基础 | Checkout支付、Webhook、L1/L2、通知、BT、退款 | PR06～PR11；演示支付成功与未知恢复，BT不重复入账，退款有预占和恢复；尚未启用分润 |
| R2 财务生命周期 | 分润、争议、payout与模拟银行 | PR12～PR14；退款/争议联动分润原子性及完整分录，出款本金与证据可复算 |
| R3 恢复与运营 | L3、独立余额对账、夜间检查、运营API和页面 | PR15～PR17；缺单发现、隔离/短款与证据不完整可见，审批与审计可用 |
| R4 可复现工程证据 | 属性/并发组合、八类故障、性能、恢复、英文材料 | PR18～PR20；证据由实际执行产生，第三人可复现，所有声明带范围与环境 |

PR05账户能力不足时可继续Fake模式的独立开发，但R0的真实契约门槛保持BLOCKED，不能把阶段标为全通过。第一个可演示支付在PR06～PR09形成；PR10～PR11补到可复算退款，避免等待全部页面和payout才展示成果。

本清单没有日历工期承诺。在前几个PR完成后，根据实际投入、学习与联调速度安排日期。每个PR允许修改已有模块以完成当前要求的接线，但不提前实现未来模块；所有跨PR接入均在路线图和提示词中标出归属。

## 19. 简历四条经历与证据要求

| 简历主张 | 对应需求 | 可以写成完成时的门槛 |
|---|---|---|
| Stripe-integrated gateway + double-entry ledger，payment/refund/dispute/payout，PSP资产与余额对账 | 第5～9、11节 | Stripe测试契约记录、全生命周期分录、独立余额/银行fixture差异报告 |
| Seven money invariants，提交检查、夜间试算、属性测试 | 第10、16节 | 7项矩阵实测；明确外部一致性非提交时保证 |
| Three-tier silent-loss protection，T+1回补 | 第11节 | 已知单和完全缺失本地支付记录分别恢复；歧义记录不自动履约 |
| Eight failure modes in CI，timeout preserves unknown | 第15节 | 八类CI产物与失败/恢复证据，模拟与Stripe契约标识 |

当前所有功能和实验均为待实施；不预设真实缺陷数量、吞吐量或成功恢复耗时。简历技术栈为“Java, PostgreSQL, Redis, Kafka, Stripe”，完成时只能采用实际验收证据；从零实现和使用AI协助的贡献边界都需如实说明。

建议最终简历用“PSP-confirmed successful payments”表达已确认收款；如果使用“PSP-settled”，必须明确指balance available还是bank payout已完成，不能混用。

## 20. 项目完成后的解释能力

作者应能独立回答：
1. 为什么Stripe已经有BT，还需要自己的ledger？
2. 为什么PI成功不能直接记银行现金？
3. 同一笔Charge的多个Webhook如何避免重复Journal？
4. 退款预占为什么会影响分润，但不立即改变PSP资产？
5. 为什么争议败诉不能再扣一次PSP余额？
6. 哪些丢单是本地轮询永远找不到的？
7. 为什么T+1不是Stripe结算时效承诺？
8. 对账如何避免“用同一份错数据证明自己正确”？
9. payout.paid与银行匹配有什么区别？
10. 属性测试发现的真实缺陷与人为mutation有什么区别？

能运行代码、复算资金、展示证据并解释取舍，才构成这个项目的完成；AI生成代码量不是完成标准。


## 21. 从需求到实施与验收的索引

以下为主要归属；完整实验定义见验收表。基础功能后续接线仍属于该需求的交付条件，不能用首个PR合并代替完整验收。

| 需求 | 主要PR | 验收编号 |
|---|---|---|
| FR-PAY-01 | PR02、PR05、PR06、PR07 | AC-V1-01、AC-V1-03、AC-V1-04、AC-V1-05、AC-V1-09 |
| FR-PAY-02 | PR05、PR06、PR08、PR17 | AC-V1-06、AC-V1-32 |
| FR-PAY-03 | PR06、PR07、PR08 | AC-V1-07、AC-V1-08、AC-V1-10 |
| FR-PAY-04 | PR09、PR11、PR15 | AC-V1-14、AC-V1-15 |
| FR-REF-01 | PR11、PR12、PR13 | AC-V1-19、AC-V1-20、AC-V1-23、AC-V1-25 |
| FR-REF-02 | PR10、PR11 | AC-V1-21 |
| FR-SHARE-01 | PR06、PR10、PR12、PR13 | AC-V1-03、AC-V1-22、AC-V1-23 |
| FR-DIS-01 | PR13 | AC-V1-24、AC-V1-25 |
| FR-PO-01 | PR05、PR14 | AC-V1-26 |
| FR-PO-02 | PR14 | AC-V1-26、AC-V1-27 |
| FR-PO-03 | PR14、PR15 | AC-V1-27、AC-V1-29 |
| FR-REC-01 | PR10、PR15 | AC-V1-16、AC-V1-18、AC-V1-30 |
| FR-REC-02 | PR15 | AC-V1-28、AC-V1-15 |
| FR-REC-03 | PR15 | AC-V1-29、AC-V1-30、AC-V1-33 |
| I-01～I-07 | PR03、PR10～PR15、PR18 | AC-V1-02、AC-V1-17～AC-V1-30、AC-V1-33、AC-V1-34 |
| F-01～F-08 | 基础用例随模块，PR19汇总故障报告 | AC-V1-35 |
| 认证/权限/安全/页面 | PR02、PR06、PR08、PR09、PR16、PR17 | AC-V1-01、AC-V1-11、AC-V1-15、AC-V1-31、AC-V1-32 |
| 夜间、性能、备份与英文成果 | PR15、PR20 | AC-V1-33、AC-V1-36、AC-V1-37、AC-V1-38 |

冻结后的OpenAPI、状态机、会计规则表、锁序和ADR应能解释本表每项测试期望。允许发现并修订规范缺陷，但必须同时修改PRD、验收及受影响提示词，记录原因和新的规则版本。
