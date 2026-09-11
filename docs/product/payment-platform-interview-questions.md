# 从零支付平台：项目面试与自测手册

版本：1.1 · 日期：2026-09-10 · 状态：设计待实施

配套文档：[阅读入口](README.md) · [产品需求](payment-platform-prd.md) · [实施路线](payment-platform-roadmap.md) · [验收与实验](payment-platform-acceptance.md) · [逐 PR 提示词](stripe-payment-ledger-codex-pr-prompts.md)

本手册围绕一个完全从零实现的 Java Stripe Payment Gateway + Double-Entry Ledger 项目，不使用、参考或改造旧支付工程，也不引入任何人的真实工作经历。技术基线为 Java 21、Spring Boot、PostgreSQL、Flyway、Kafka、Redis。当前只有设计材料；下文的“工程证据”均为未来应交付的证据清单，不表示代码、测试、Stripe 账户能力或性能已经验证。

共 **41 道技术题**，另有 **3 道白板练习**。P0 表示理解本项目核心正确性的学习优先级，P1 表示把设计落实为工程，P2 表示扩展与取舍；这些标记不预测任何公司的面试题、录用概率或淘汰规则。每题按“问题 → 回答逻辑 → 追问 → 证据”练习，先用自己的话解释，再打开材料核对。

业务范围、会计账户、C/R/H、分润冻结、恢复周期和测试目标均为**本项目自定政策**。Stripe、PostgreSQL、Kafka 的外部行为在对应题目附近引用官方资料，核对日期为 2026-09-10；文档核对不等于账户契约实测，也不代表选定了网页所示的软件版本。依赖版本在 PR00 记录，在 PR01/PR05 实际验证。

## 一、两分钟介绍模板

### 中文：设计阶段可直接练习的版本

我正在设计一个从零实现的 Java 支付平台，计划使用 Spring Boot、PostgreSQL、Stripe，以及 Kafka 和 Redis。它面向一个数字商品销售主体，先支持一个 Stripe 测试账户、一次性卡支付和自动 capture，客户通过 Stripe 托管 Checkout 完成付款。

我想解决的是：网络超时或通知丢失以后，系统怎样持续解释客户有没有付款、退款占用了多少额度、合作方还能计提多少，以及资金是在 Stripe、出款途中还是已有银行到账证据。

设计上，我把业务意图、提供方执行结果、Balance Transaction 资金事实和银行证据分开。支付结果未知时保留 UNKNOWN，通过持久操作和恢复任务调查；退款用 C/R/H 控制并发额度；外部资金按 BT 唯一入账，内部分润用独立事件追加凭证。架构采用模块化单体，API 和 Worker 可以独立运行，但关键资金动作仍在同一个 PostgreSQL 事务域内。

验证计划包括七项资金不变量、独立模型的属性测试、八类故障注入，以及使用独立期末证据的对账。目前这些是设计和验收目标，尚不能当成已实现成果。后续我会用可复现命令、提交版本和实际报告说明验证到了哪一步；银行证据使用明确标识的模拟文件，生产资金和合作方实付不在本期。

### English: a design-stage introduction

I am designing a payment gateway and a double-entry ledger from scratch in Java. The planned stack includes Spring Boot, PostgreSQL, Stripe, Kafka, and Redis. The initial scope is one digital-goods seller, one Stripe test account, and one-time card payments through hosted Checkout.

The problem I want to address is how to explain the state of a payment and its money when requests time out, notifications are lost, refunds overlap, or payouts lack bank evidence.

The design separates business intent, provider execution, balance transactions, and bank evidence. An uncertain response remains UNKNOWN until it can be resolved. Refund reservations protect the remaining refund capacity, while partner accruals follow an explicit revenue-share ceiling. External funds movements have stable accounting identities and produce immutable journals.

I plan to use a modular monolith with separate API and worker roles. Financial changes that must be atomic remain within one PostgreSQL transaction. The validation plan includes seven money invariants, independent model-based tests, eight failure modes, and reconciliation against independent closing evidence.

The project is currently at the design stage. I will distinguish implemented behavior from planned behavior and only report results that have reproducible evidence. Simulated provider load and bank fixtures will be clearly labeled, and this scope does not include production money movement or real partner payouts.

实现以后，只把实际完成的部分改成完成时。例如：“在 `[commit SHA]`、`[环境和数据量]` 下，我运行了 `[命令]`，观察到 `[结果]`；尚未覆盖 `[限制]`。”没有报告时不填数字，也不把 mutation 人为注入问题写成真实发现的缺陷。

## 二、项目定位与从零架构

### Q01 · P0 · Stripe 已经有交易记录，为什么还需要这个项目和本地账本？

**回答要点：** Stripe 记录提供方侧事实；本地还要保存可信订单、退款资格、履约通知、合作方应付和人工调整。复式账本使内部资产与义务可追溯，但“另建一套表”本身不构成独立对账。账本若由 BT 导入生成，还要用独立期末证据和覆盖检查发现导入遗漏。项目价值在于可以解释并验证资金变化。

**追问：** 若时间减半，保留什么？应先保留一个真实 Checkout 契约和支付、恢复、BT 入账、独立对账的纵向闭环，再缩减展示范围。

**工程证据：** PR00 的范围与 ADR；一笔订单到 Provider、BT、Journal、差错记录的可追溯样例；AC-V1-38。

### Q02 · P0 · 为什么选择模块化单体，而不是一开始拆成微服务？

**回答要点：** 本期规模和团队边界没有证明需要服务拆分。退款预占、分润撤回、凭证和投影需要共同提交，先放在同一 PostgreSQL 事务域更容易说明正确性。模块通过应用接口协作，Stripe SDK 留在适配层。API/Worker 是运行角色，可分进程部署；它们不自动等于拥有独立数据的微服务。

**追问：** 何时考虑拆分？要先有独立扩缩容、故障隔离或团队所有权的证据，并重新设计跨服务一致性与运营恢复。

**工程证据：** PR00 模块依赖图、事务边界表；PR01 两类运行角色配置；没有跨业务包直接依赖 Stripe SDK 的检查。

### Q03 · P1 · PostgreSQL、Kafka、Redis 分别承担什么，哪些组件故障不能破坏资金真相？

**回答要点：** PostgreSQL 保存订单、操作义务、幂等键、凭证和 Outbox；Kafka 传递可重复消息；Redis 可用于缓存或非权威限流状态。Redis 丢失不能释放业务唯一性，Kafka 不可用应表现为可见积压。关键状态和待发布消息先同事务持久化，再异步传递。

**追问：** 为什么仍保留 Kafka？用于验证发布、消费和恢复边界；若没有独立消费需求，可说明省去它的取舍，不能靠组件数量证明架构水平。

**工程证据：** PR09 的 broker 中断实验、Outbox 积压与恢复；清空测试缓存后幂等性不变的证据；AC-V1-14。

### Q04 · P0 · 只有一个真实测试账户，为什么还要设计 AccountScope 和认证隔离？

**回答要点：** 本期只有一个实际组织、Stripe 测试账户和运行币种，额外账户由 fixture 提供。请求中的组织 ID 不能决定权限；可信应用凭据绑定 TenantContext，再校验资源归属。外部事实带组织、Provider 账户、环境/livemode、币种和余额域。银行及内部应付账户保留明确来源，但不强行伪装成 Stripe 账户。

**追问：** 一个合法用户拿到别的组织的 Journal ID，会发生什么？查询和关联写入都应拒绝，不仅在页面隐藏。

**工程证据：** PR02 认证契约、跨 scope 的查询与复合关联约束测试；PR03/PR10 的跨币种和跨余额域案例；AC-V1-01/02/18。

### Q05 · P1 · 为什么金额用最小货币单位整数？有哪些整数方案仍会出错？

**回答要点：** 本项目用精确整数保存金额，USD 10000 表示 100.00 美元；JPY 以另一套零小数配置测试，不做汇兑。仍要防止乘法溢出、负数方向混乱和入参精度丢失。分润先用足够精度计算，再按政策向下取整。导入时验证每个字段单位，不能看到数字就当 cents：Stripe 的部分余额报告列使用主货币单位。[Stripe 余额报告列定义](https://docs.stripe.com/reports/balance)

**追问：** `amount * rateBps` 溢出怎么办？使用已验证的范围限制或足够大的精确中间类型，不能静默截断。

**工程证据：** PR00 金额 ADR；PR02/PR03 USD、JPY、越界和报告单位转换样例；AC-V1-02。

## 三、Stripe 对象契约与支付状态

### Q06 · P0 · Checkout Session、PaymentIntent、Charge、BT 各表示什么？

**回答要点：** Checkout Session 承载客户托管支付流程；PI 跟踪支付生命周期；Charge 保留具体尝试结果；BT 描述对 Stripe 余额的净影响。一个 PI 可能关联多次 Charge 尝试，不能只留一个“渠道流水号”。BT 保留 amount、fee、net，满足 `net = amount - fee`。[Stripe PaymentIntent](https://docs.stripe.com/payments/payment-intents) · [BT 对象](https://docs.stripe.com/api/balance_transactions/object)

**追问：** 创建 Checkout 成功是否已经扣款？没有；初始 Session 的 PI 关联也可能尚未取得，需要后续补齐。同一支付的客户动作、执行结果、资金证据还要分别跟踪。

**工程证据：** PR05 的真实测试契约及脱敏对象关联；多次 attempt 记录；PR06 查询响应；AC-V1-05/06。

**短英文：** “A Checkout Session is the customer flow; a PaymentIntent tracks payment execution, while balance transactions describe changes to the Stripe balance.”

### Q07 · P0 · 怎样保证金额和订单归属可信？metadata 可以直接作为证据吗？

**回答要点：** 应用提交受控商品/价格引用，服务端验证后固定订单金额和分润政策快照；不信任客户传来的最终金额。metadata 是关联线索，要与服务端创建约定、本地订单、金额币种和账户一起验证。Stripe metadata 通常不会自动复制到相关对象；Checkout 的 `payment_intent_data.metadata` 用于设置 PI metadata，PI→Charge 复制也有快照语义。[Stripe metadata](https://docs.stripe.com/metadata)

**追问：** Dashboard 上有人修改 metadata 呢？不能因此更换订单归属或自动履约，应保留证据并调查。

**工程证据：** PR02 受控价格 fixture；PR05 metadata 契约；PR06 金额不可覆盖及快照测试；AC-V1-03/05/28。

### Q08 · P0 · “客户点取消”“Checkout 过期”“支付已取消”有什么区别？

**回答要点：** 本地记录取消意图；适配器根据实际 Checkout/PI 状态采取支持的操作，并查询确认。Stripe 的 expire Session 接口只适用于 `open` Session，不能把一个通用 PI cancel 直接当成所有 Checkout 场景的取消实现。[Stripe Session 过期接口](https://docs.stripe.com/api/checkout/sessions/expire) 本项目政策是回跳、关闭网页和取消请求均不能独立否定已有成功 capture；竞争或响应未知进入调查。

**追问：** 取消过程中成功通知到了呢？保留原 capture 事实，更新取消结果及冲突证据；若需退钱，走退款资格与执行流程。

**工程证据：** PR05/PR06/PR07 的 Session 状态矩阵、取消竞争测试和客户 UNKNOWN 页面；AC-V1-07。

### Q09 · P0 · 订单唯一键、API 幂等键、Provider 操作键为什么不能混为一谈？

**回答要点：** 订单键标识业务购买；API 幂等键标识一次有确定语义内容的命令；Provider 操作键标识一次外部写操作，它们都不同于认证用的 API 密钥。对同 scope 同幂等键，内容一致返回原资源，冲突返回 409；改变 key 也不能绕过同订单的业务唯一性。创建 Session、申请退款各有不同语义。指纹按规范化业务字段计算，避免 JSON 字段顺序造成假冲突。

**追问：** 同订单换新 key、同 key 换币种、两实例同时请求，分别如何处理？先说数据库约束，再说 API 响应和既有资源关联。

**工程证据：** PR00 API 错误契约；PR06 唯一键、请求指纹和并发测试；AC-V1-04。

## 四、UNKNOWN、持久操作与恢复

### Q10 · P0 · 调用 Stripe 超时为什么不能直接判失败？

**回答要点：** 本地没收到结果不等于外部没受理。操作状态与支付事实分开：保存最后已知 providerStatus，resolution 进入 UNKNOWN，使用原操作关联查询、通知和扫描收敛。只有能证明未发出的失败，才按安全条件重试；不能把所有网络错误都归为发送前失败。最终 capture 证据才能决定已收款金额。

**追问：** Stripe 返回 500 能否换 key 再试？仍需调查操作效果；Stripe 的幂等响应还可能保存首次 500，换 key 不是解决未知结果的方法。[Stripe 幂等请求](https://docs.stripe.com/api/idempotent_requests)

**工程证据：** PR04 “受理后丢响应”脚本；PR07 的 UNKNOWN→已查明路径、Provider 独立效果计数；AC-V1-08。

**短英文：** “A timeout describes missing information. I preserve the original operation and resolve its outcome before allowing another money-moving attempt.”

### Q11 · P0 · 幂等键过期了，外部 ID 也没拿到，还能沿用原 key 重发吗？

**回答要点：** 不能把沿用同 key 等同于永久去重。Stripe 文档说明，key 至少保存 24 小时后可以移除；被移除后再用同 key 会成为新请求，并非固定在第 24 小时整失效。[Stripe 幂等保留语义](https://docs.stripe.com/api/idempotent_requests) 本项目要保存首次调用时间、请求内容与关联证据，窗口外先调查；无法排除已受理时保持 UNKNOWN/升级，禁止自动创建第二个支付。

**追问：** 怎么测试这个边界？Fake 的时间前进后删除 Provider 侧去重记录，让错误重发真正产生第二次效果，从而验证本地保护。

**工程证据：** PR04 的过期模拟；PR07/PR11 的过期操作恢复与挂起证据；AC-V1-09。

**短英文：** “Reusing the same key is safe only within the provider’s verified guarantees; an expired unknown operation requires investigation.”

### Q12 · P1 · 进程重启、租约过期和旧 Worker 回写，怎样保证同一操作不会被错误推进？

**回答要点：** 订单/退款、通用 provider_operation 和持久执行义务同事务提交，网络调用在外部。领取任务时生成递增版本或 lease token，结果回写同时校验当前租约和业务版本。过期 Worker 的结果可保存为观察证据，不能越过状态机覆盖新事实。租约只协调本地执行，不保证 Stripe 只收到一次请求；外部效果仍依赖操作幂等和过期保护。

**追问：** 事务提交后、发送前崩溃和受理后崩溃有什么不同？分别展示能证明未发送的路径和需要 UNKNOWN 调查的路径；退款复用同一恢复框架。

**工程证据：** PR07/PR11 双 Worker、重启、过期回写和退款重领实验；AC-V1-10。30 秒租约、特定本地条件下 120 秒内重领是项目实验目标。

## 五、事件、消息与业务效果

### Q13 · P0 · Webhook 接收器应该做多少事？为什么验签必须在解析业务前？

**回答要点：** 验证原始字节、签名和对应端点配置，再持久化 Inbox；提交成功后快速返回 2xx，复杂状态和账务处理异步执行。持久化失败不能报成功。Stripe 验签要求未经修改的原始请求体；框架重新序列化 JSON 可能改变字节。[Stripe Webhook 验签与接收建议](https://docs.stripe.com/webhooks)

**追问：** 签名合法是否意味着该记录必然属于当前组织且能直接记账？不是，还需要账户范围、对象身份和财务证据校验。

**工程证据：** PR08 原始字节 fixture、篡改拒绝、持久化失败返回及零业务副作用测试；真实 Stripe 与自签模拟来源分别标识；AC-V1-11。

### Q14 · P0 · 同 event 重投和多个 event 描述同一笔收款，分别在哪里去重？

**回答要点：** Inbox 按账户/环境/eventId 去重；财务层按组织、Stripe 账户、livemode、BT ID 的稳定键去重。状态事件和扫描都解析为规范观察，再由同一个财务入口接受资金事件。Stripe 可能重复投递，也可能为相关变化生成不同 Event；只做 eventId 去重不足以保证资金只记一次。[Stripe 重复事件说明](https://docs.stripe.com/webhooks)

**追问：** 一个 Webhook 对应一张 Journal 吗？不固定，可能没有新资金事实，也可能发现多个 BT；内部计提另有业务事件键。

**工程证据：** PR08/PR10 同 event、不同 event、BT 重扫的组合记录，event/BT/Journal 计数；AC-V1-12。

**短英文：** “Delivery identity and accounting identity are different; multiple observations of one balance transaction must resolve to one journal.”

### Q15 · P1 · 事件乱序能靠时间戳或状态大小比较解决吗？

**回答要点：** Stripe 不保证事件送达顺序，事件时间也不能作为通用排序去重键。[Stripe 事件顺序说明](https://docs.stripe.com/webhooks) 本项目保存 provider 原值、观察版本和来源，以权威查询及资金事实处理冲突。依赖缺失的 BT 先持久化等待；成功 capture 不被较晚到达的旧尝试失败抹掉，争议 late win 也不能被“终态永不变”拦截。

**追问：** BT 比 Payment 更早到达，是否直接创建订单？先验证映射；没有可信订单只能保留观察或未分配资金。

**工程证据：** PR08/PR10/PR11/PR13 乱序依赖等待与恢复；AC-V1-13。

### Q16 · P0 · Outbox 和 Consumer Inbox 分别消除什么失败窗口？

**回答要点：** 业务状态和 Outbox 同事务解决“状态成功、通知义务丢失”。发布后标记完成前崩溃仍可能重复发布；消费端把 Inbox 去重记录与本地业务效果同事务写入，提交后才确认消费进度。失败重投返回已有结果，不能再应用一次额度或凭证。不要把网络发送和数据库更新描述成原子事务。

**追问：** 为什么先提交 Kafka offset 再写数据库危险？提交后崩溃可能跳过业务效果；反过来则需要处理重复。[Kafka 消息交付语义](https://kafka.apache.org/41/design/design/#message-delivery-semantics)

**工程证据：** PR09 发布前后、消费提交前后四个注入点的数据库与消息证据；AC-V1-14。

### Q17 · P0 · 能不能声称系统做到端到端 exactly-once？

**回答要点：** 必须限定参与方和效果。Kafka 的事务语义不能自动把任意 PostgreSQL 写入或商户 HTTP 履约纳入同一事务；外部目的地需要配合。[Kafka 对外部系统的语义边界](https://kafka.apache.org/41/design/design/#message-delivery-semantics) 本项目可证明稳定 eventId、重试、商户持久去重及业务效果同事务的模拟合同下效果一次。对不支持幂等的任意商户，不能承诺丢响应后既不漏发又不重复。

**追问：** 商户已发货但响应丢了怎么办？原 eventId 重试，由商户返回已处理结果；历史回补还必须重新判断履约资格。

**工程证据：** PR09 模拟商户持久履约计数和丢响应实验；PR15 历史订单资格案例；AC-V1-15。

**短英文：** “Exactly-once business effects require cooperation at the destination; Kafka transactions alone do not make an arbitrary HTTP side effect atomic.”

## 六、退款与分润

### Q18 · P0 · C、R、H 各是什么？1000 的支付遇到两笔并发 600 退款会怎样？

**回答要点：** C 为确认 capture，R 为成功退款累计，H 为已批准但结果未明确的预占。本项目 NORMAL 控制行要求 `0 <= R + H <= C`。两个请求都要锁同一稳定控制行，在同一短事务检查、增加 H、创建退款及操作义务；先获锁者预占 600，后者只能看到剩余 400 并被拒绝。不能先调用 Provider 再补预占。

**追问：** Java synchronized 能否保护双实例？不能代替数据库并发控制；也要说明幂等重复请求不会再增加 H。

**工程证据：** PR11 真实 PostgreSQL 并发屏障、成功/拒绝各一次、C/R/H 和 Provider 效果数；AC-V1-19。

**短英文：** “A refund reserves capacity before the external call, so concurrent requests cannot spend the same refundable amount.”

### Q19 · P0 · 退款 UNKNOWN、pending、成功、失败分别怎样改变预占和账本？

**回答要点：** UNKNOWN、pending 和尚需动作时保留 H；成功把对应 H 转为 R；有明确失败/取消证据才幂等释放 H。退款状态与资金事实分别确认，预占不减少 PSP 资产。若失败产生返还 BT，以新事实记回，不能删除原退款 Journal。Stripe 有 pending/failed 退款及 failure_balance_transaction，原处理费不能按退款比例假设退回。[Stripe 退款与失败返还](https://docs.stripe.com/refunds)

**追问：** 已经看见退款资金返还、业务状态还旧，能否直接释放所有预占？先确定返还对应哪笔退款，并按状态/证据冲突规则处理。

**工程证据：** PR10/PR11 退款双维度时间线、重复结束、超时恢复和返还 BT；AC-V1-20/21。

### Q20 · P0 · Dashboard 上发生外部退款，如何避免与本地预占重复计算？

**回答要点：** 外部事实必须摄入，但不能不加判断地令 `R += amount` 后保留同一退款的 H。先核对 providerRefundId、来源操作与金额；映射不明或与已有意图冲突时标记 EXTERNAL_CONFLICT、停止新退款，并保留原证据。NORMAL 的乐观额度不变量不能成为丢弃外部事实的理由。确认是同一笔后再原子修复映射与 C/R/H。

**追问：** 本地还有另一笔 UNKNOWN 退款呢？先冻结进一步申请并查询，不能释放未知预占以让表面数字变好看。

**工程证据：** PR11 Dashboard/fixture 外部退款与本地 UNKNOWN 重叠案例、冻结和解除条件；AC-V1-20。

### Q21 · P0 · 收款 10000、分润 20%、预占退款 4000，分润应怎样变化？

**回答要点：** 在已具备收款记账证据、没有阻断条件时，原有效基数 10000、计提 2000。增加 H=4000 后 `eligibleBase=6000`、`cap=floor(6000*2000/10000)=1200`，撤回 800。退款成功使 H 转 R，cap 仍是 1200，不能再撤一次；明确失败释放 H，按原政策幂等恢复到 2000。这是本项目保守内部计提政策，未发生合作方实付。

**追问：** 分润为什么立即撤回而 PSP 余额没变？内部义务已变，外部资金须等待 BT。

**工程证据：** PR12 的数字复算、撤回/恢复 Journal、重复成功及失败序列；AC-V1-22/23。

**短英文：** “A refund reservation reduces the partner’s accrual ceiling immediately, while PSP assets change only when a balance transaction provides evidence.”

### Q22 · P1 · 多合作方比例、政策变化和碎片退款怎样处理？

**回答要点：** PR06 下单时快照政策，后续计算读取快照，不能用当前配置重算历史订单。每个合作方独立 `floor(eligibleBase*rate_i/10000)`，比例合计不超过 10000，余数归平台。根据累计 R/H 后的基数重算 cap，再与有效计提比较；不能把每次退款独立取整的撤回额相加。

**追问：** 比例为 3333/3333/3334、金额只有几个最小单位时，合计是否会超基数？展示独立取整后的数值及余数归属，而非只背公式。

**工程证据：** PR06 政策不可变快照；PR12 边界金额、多伙伴与碎片退款属性样例；AC-V1-03/22。

### Q23 · P0 · 只更新 share_allocation，算完成分润了吗？何时允许首次计提？

**回答要点：** 没有。必须在收款 BT 已正式入账且没有阻断条件后计提；PI 成功但缺 BT 时计提为零。初次计提、撤回、恢复均以稳定内部事件键追加平衡 Journal，并同事务更新 allocation。退款资格变化与所需分润撤回同事务；争议未决、败诉或资金矛盾使整笔 eligibleBase=0。胜诉恢复需资金证据完整且无其他阻断。

**追问：** 分润模块启用前已入账的历史支付怎么办？基于下单快照做可重复的 catch-up，不能随机生成事件键导致再计提。

**工程证据：** PR12 初次计提/历史补计提/退款联动，PR13 争议接线；allocation 与 Journal 回滚一致性；AC-V1-23/25。

## 七、复式账本与数据库约束

### Q24 · P0 · 用具体数字说明收款、退款和 payout 为什么不是同一种余额变化？

**回答要点：** 以下为项目演示账务政策，均以 USD cents 表示。Charge BT 为 amount=10000、fee=300、net=9700：借 PSP_FUNDS 9700、PSP_FEE_EXPENSE 300，贷 SALES_REVENUE 10000。Refund BT=-4000 且无费用返还：借 SALES_RETURNS 4000、贷 PSP_FUNDS 4000。无费用 payout 本金 5700：借 BANK_PAYOUT_IN_TRANSIT、贷 PSP_FUNDS；银行证据确认后才借 BANK_CASH、贷在途。分润另计 PARTNER_SHARE_EXPENSE/PARTNER_PAYABLE。

**追问：** PI 已 succeeded、BT 没拿到能记哪笔？可确认支付执行结果并显示 PENDING_EVIDENCE，不能估算 fee 后正式记 PSP 账。

**工程证据：** PR03/PR10/PR12/PR14 的可手算 Journal、证据关联和时间线；AC-V1-17/21/23/26。

**短英文：** “Payment success, PSP funds, and bank cash are separate facts, and each accounting transition needs its own evidence.”

### Q25 · P0 · 禁止 UPDATE/DELETE 就足以保证 Journal 不可变吗？怎样防止封存后追加 Entry？

**回答要点：** 还要禁止向已封存凭证 INSERT 分录；即使新增一对平衡行，借贷总额仍相等，历史含义却变了。设计受控的一次性过账入口，在同一事务创建最终 Journal/Entry、校验并封存，不向应用开放任意表 DML。新事务补行、再次调用入口追加、删改旧行均被拒绝。修正生成带原因、证据及原凭证关联的新 Journal。

PostgreSQL 普通 CHECK 不适合表达跨行总和；可用受控入口结合提交时约束触发器检查最终集合。具体实现还必须覆盖空 Journal、并发追加和应用角色权限，不能声称“有一个触发器就足够”。[PostgreSQL CHECK 限制](https://www.postgresql.org/docs/current/ddl-constraints.html) · [约束触发器](https://www.postgresql.org/docs/current/sql-createtrigger.html)

**追问：** 两次合法过账调用都指向同一 Journal 呢？入口接受稳定事件身份和完整分录集合；重复返回原结果，语义冲突拒绝，不允许追加。

**工程证据：** PR03 用应用数据库角色执行空凭证、失衡、封存后单行/平衡双行补插、修改/删除和并发案例；AC-V1-17。

**短英文：** “An immutable journal must reject appended entries as well as updates and deletes; balance alone does not protect its historical meaning.”

### Q26 · P1 · 退款、分润和多个账户同时锁定时，怎样处理死锁？

**回答要点：** 所有入口采用 PR00 固定的全局锁顺序，例如先相关业务控制行，再按稳定 ID 顺序锁账户及财务事件，禁止一条路径反向取锁。网络调用在事务外。PostgreSQL 会检测死锁并中止其中一个事务；应用对可重试的整笔事务做有界重试，并沿用原命令/事件键。[PostgreSQL 显式锁与死锁](https://www.postgresql.org/docs/current/explicit-locking.html)

**追问：** 序列化失败是否只重跑最后一条 SQL？不能假定前面的读取仍有效，应重新执行完整事务逻辑，并保留幂等性。

**工程证据：** PR03/PR11/PR12 的锁序表、反向锁故障案例、整事务重试和零重复 Journal；真实 PostgreSQL 屏障实验。

### Q27 · P0 · 七项不变量中，哪些能在提交时拒绝，哪些必须等证据和扫描？

**回答要点：** I-01 平衡、I-02 唯一性、I-03 NORMAL 退款额度、I-04 当前分润上限，以及 I-07 的跨域写保护，需要本地约束与事务保障。I-02 的“所有接受事件都覆盖”、I-07 的独立余额重建还需要扫描。I-05 外部 PSP 对齐要完整 BT 与独立期末证据；I-06 本地匹配限额可提交拒绝，但在途、银行、返还的外部一致性仍依赖证据。

**追问：** 本地全通过而期末证据缺失，应显示什么？对应对账结论是 INCOMPLETE，不能把缺材料当 PASS。

**工程证据：** PR03/PR11/PR12 提交拒绝测试，PR15 夜间规则报告，PR18 独立模型；七项分别标注检查时点。

### Q28 · P1 · “每个事件恰好一张凭证”和“余额可重建”如何落到数据库？

**回答要点：** 未满足证据条件的 observation 不叫已接受财务事件；接受 event、完整 Journal/Entry 和投影同事务提交，事件 key 与 event→Journal 关联唯一。所有投影增量只由 LedgerPostingService 更新，Importer 不再加一次。重建固定截止点，用不可变分录和已核对期初独立计算；对不上先报告原因，再受控重建，不能改账掩盖问题。

**追问：** UNIQUE 是否能保证每个事件都有 Journal？它通常只约束最多一个；同事务入口、必要约束与覆盖扫描一起处理缺失。

**工程证据：** PR03 同事务回滚及重建，PR10 重复 BT 不二次更新投影，PR15 coverage 扫描；AC-V1-17/33。

## 八、财务导入、对账与缺单恢复

### Q29 · P1 · 导入过一页 BT 后，什么时候可以声明窗口 COMPLETE？

**回答要点：** 先固定账户范围、创建时间窗口、业务时区转换和证据版本，持久化分页 cursor 与页面进度，全部页面完成后才推进最终水位。崩溃重跑允许重复观察，但不得重复资金效果。Stripe BT 列表支持时间区间和分页游标。[Stripe BT 列表](https://docs.stripe.com/api/balance_transactions/list) 本项目采用重叠回看与 pending 跟踪；pending→available 只刷新可用性，不再增加 PSP 总额。

**追问：** 7 天回看覆盖所有历史吗？不是，7 天是本项目补偿设置；晚到证据重跑受影响日期，另支持授权历史 backfill。

**工程证据：** PR10 分页中断、末页前崩溃和重复页；PR15 时区边界与晚到重跑；AC-V1-16/30。

### Q30 · P0 · 遇到未知 BT 类型或同 ID 不同金额，能否跳过以保持流程继续？

**回答要点：** 同 scope 同 ID 的 amount/fee/net 冲突是证据冲突，应保存版本、隔离调查，不能覆盖旧事实或再记一笔。未知类型分两类：本账户 payments 余额域、金额已验证的事实按项目政策先记 PSP↔Suspense；其他余额域、FX 或归属不足的记录隔离，不混入 payments 对账。未知不能静默丢弃，未覆盖范围必须可见。

**追问：** 进入 Suspense 后财务事件算处理完了吗？资金影响可以已反映，但业务分类仍未完成；审批重分类不应再改变同一笔 PSP 净额。

**工程证据：** PR10 冲突、未知 payments BT、跨域隔离和重分类测试；PR16 审批链；AC-V1-18。

### Q31 · P0 · 怎样避免“用同一份漏数据证明自己没漏账”？

**回答要点：** 将账本控制余额与 `期初 + 已导入 BT 净变动` 比较，可发现映射错误和漏入账，但不能独立证明 BT 抓取完整，因为两者可能共享同一遗漏。还要取独立期末报告/快照，统一账户、币种、余额域和截止点，检查分页完整度及未分类项。Stripe Balance API 是当前余额，余额报告可提供所选期间的期初/期末，不能拿今天实时值冒充昨天期末。[Stripe 当前余额](https://docs.stripe.com/api/balance) · [Stripe 余额报告](https://docs.stripe.com/reports/balance)

**追问：** 测试账户没有所需历史报告怎么办？使用可控静止区间快照或标为 INCOMPLETE；独立 Provider/银行 fixture 可验证算法，但要标明 fixture-only，不能说真实历史报告已验证。

**工程证据：** PR15 主动漏掉一条导入数据的残差报告、独立外部输入、scope/截止点和完整度；AC-V1-29。

**短英文：** “Comparing imported transactions with the ledger checks local posting; independent closing evidence is also needed to detect missing source data.”

### Q32 · P0 · L1/L2/L3 分别能发现什么？发现外部成功就能自动发货吗？

**回答要点：** L1 查询刚提交的已知操作；L2 扫本地 UNKNOWN、过期租约和缺 BT；L3 从外部 BT 枚举，才可能发现本地整条 payment 缺失。恢复要沿 BT→Charge→PI 核对 scope、成功 capture、金额币种和唯一可信订单。订单仍在时可恢复支付关联；连可信订单也没有，只保留未分配财务观察，禁止捏造订单与自动履约。

**追问：** T+1 是否表示次日结算或所有问题一天内恢复？不是，是本项目次日执行发现与核对任务的设置；履约还需有效订单资格。

**工程证据：** PR07 已知操作恢复；PR15 缺 payment/有 order、缺 order、金额冲突三个案例；AC-V1-08/15/28。

## 九、争议、payout 与运营边界

### Q33 · P0 · 争议扣款、败诉、胜诉和 late win 各如何影响账本？

**回答要点：** 按项目政策，真实扣款 BT 借争议应收/实际费用、贷 PSP；败诉将仍存在的应收转损失，不再扣 PSP。返还按新 BT 记回 PSP，先结清可关联应收，再冲回已确认损失，超出解释范围进 Suspense。inquiry/warning 不能仅靠状态推断扣款。Stripe 确认存在 lost 后改为 won 的少数 late-win 情况。[Stripe 争议生命周期](https://docs.stripe.com/disputes/how-disputes-work)

**追问：** 部分退款后争议全额、同 Charge 多个争议怎么办？保留真实金额和全部关联，冻结新普通退款和分润，不能用 `R + 争议金额` 直接当退款额度。

**工程证据：** PR13 状态/BT 先后顺序、败诉不重复扣款、late win 拆分及重叠退款；AC-V1-24/25。

### Q34 · P0 · payout.paid 为什么不是银行到账？返还和银行匹配重叠时怎么办？

**回答要点：** Payout 资金扣减先转在途；独立银行记录核对后才转 BANK_CASH。失败返还通过新的 failure_balance_transaction 反映，不删除原扣减。[Stripe Payout 对象](https://docs.stripe.com/api/payouts/object) 本项目约束同笔本金不能同时全额银行到账又全额返还；重叠证据转冲突并调查银行退回，费用与本金分开。手动 payout 不能用 FIFO 冒充官方订单归属，BT 的 payout 筛选明确针对自动 payout。[Stripe payout 筛选范围](https://docs.stripe.com/api/balance_transactions/list)

**追问：** payout=5700、到账=5500 是否必然短款？先确认完整银行证据、时间窗及是否有单独已验证的 200 银行费；缺证据要等待。

**工程证据：** PR14 部分匹配、重复匹配、返还重叠及模拟银行标签；PR15 SHORT_RECEIPT/UNMODELED_FEE；AC-V1-26/27/29。

### Q35 · P1 · 运营能“手工修复”到什么程度？安全边界如何证明？

**回答要点：** 可查询、重试、发起有证据的调整与银行 fixture 导入；敏感调整需独立复核，创建者不能自审，只追加凭证。可信凭据绑定组织和权限，操作记录 actor、原因、证据、目标、结果。默认拒绝 live key；后端不收原始卡号；回调地址按受控规则防止访问内部地址。日志删除密钥、client secret 和敏感字段，不因调试临时放开。

**追问：** 已审批的调整重复提交、绕过页面直接调用 API 呢？服务端权限、状态条件、稳定事件键和审计必须继续生效。

**工程证据：** PR02/PR16 越权、自审、重复审批、回调目标限制；PR17 日志脱敏；AC-V1-01/31/32。

### Q36 · P1 · 怎样证明“最终一致”正在收敛？夜间任务应该输出什么？

**回答要点：** 看 UNKNOWN 最老年龄、退款预占老化、Webhook lag、Outbox 积压、缺证据、未分类 BT、对账残差和 payout 在途，而非只看接口成功率。夜间按固定高水位做试算、退款/分润检查、事件覆盖、余额重建与 PSP/银行核对，输出 PASS/FAIL/INCOMPLETE、规则版本和残差。工单重驱也要留证据，不能点击“解决”就把差额清零。

**追问：** 请求量下降导致 UNKNOWN 数量下降，是否说明恢复了？还要看同批操作的年龄、结论和资金证据。

**工程证据：** PR15 夜间运行报告和复发工单；PR17 指标面板及时间线；AC-V1-32/33。

## 十、验证、性能、恢复与个人解释能力

### Q37 · P0 · 属性测试怎样避免只是在重复生产实现？

**回答要点：** 使用独立纯模型解释 C/R/H、分润、PSP 和在途变化，生成合法及应拒绝序列，不调用生产计算函数当期望值。每个本地事务后检查即时不变量，外部事实收敛后检查 I-05/06。保留 seed、完整序列、shrink 最小反例和回归测试。随机串行序列不等于并发测试，还需真实数据库屏障。Fake Provider 的外部事件流也要独立记录。

**追问：** 预置“错误分润公式”被测出来能算真实缺陷吗？应记 mutation 敏感性实验；只有实际实现中的问题才能按证据称为缺陷。

**工程证据：** 模块随 PR 的基础模型；PR18 的 jqwik 组合模型、shrinker、失败或通过的真实报告；AC-V1-34。PR 默认 100 seeds、每序列最多 100 步，夜间默认 1000 seeds、最多 300 步，按 CI 预算校准并记录实际数量；这些数量本身不证明覆盖全部事件组合。

### Q38 · P1 · 八类故障为什么要分别统计请求数和资金效果数？

**回答要点：** 重试允许请求、Webhook、消息计数大于一；资金效果、业务成功效果和规范财务 Journal 的计数有各自约束。八类为丢、重复、乱序、篡改 Webhook，Provider 超时、进程崩溃、broker 中断/重投、并发命令。每类报告记录环境、commit、seed、注入点、预期、实际与多方证据，不把一张页面截图当正确性证明。

**追问：** 进程崩溃测哪些窗口？提交意图前后、Provider 受理前后、Inbox/Outbox/消费提交前后及财务提交边界都应有归属清楚的案例。

**工程证据：** PR19 F-01～F-08 矩阵、Provider 独立效果账、恢复耗时和零重复凭证查询；AC-V1-35。

### Q39 · P1 · 如何陈述性能结果，才不会把模拟吞吐当成 Stripe 生产能力？

**回答要点：** 先说明是否只是目标。本项目目标环境为双 API/双 Worker、100 万历史支付、本地模拟提供方下 100 RPS 写受理加 200 RPS 查询，预热 5 分钟、运行 30 分钟；受理 P95≤300ms、P99≤1s、非注入错误<0.1% 均待验证。实测须带硬件、版本、commit、请求构成及效果计数，区分受理与支付完成。Stripe 明确不建议用测试环境压测，应与少量真实契约联调分开。[Stripe 测试说明](https://docs.stripe.com/testing)

**追问：** P95 通过而恢复队列不断增长算成功吗？不算完整通过；还要报告积压、资源使用、错误率和正确性门槛。

**工程证据：** PR20 原始负载报告与瓶颈分析，未运行项写 NOT RUN；AC-V1-36。

### Q40 · P1 · 数据库备份恢复成功后，为什么不能马上允许支付写入？

**回答要点：** 恢复点可能早于 Stripe 已发生的操作，数据库可启动不表示外部状态对齐。只在新工程专用隔离测试库演示：恢复备份、核对控制账户和事件覆盖、重扫受影响 Stripe 范围、恢复任务及消息处理，再决定放开写入。对过旧、无 ID 的未知操作仍执行过期幂等保护。记录实际恢复耗时、恢复点、损失窗口和无法自动补回的业务数据。

**追问：** 连原订单也没恢复出来怎么办？保留外部财务观察，人工调查；不能为恢复流畅而编造订单或自动履约。

**工程证据：** PR20 恢复手册、隔离库身份、备份与恢复日志、范围重扫报告、写入恢复门槛；AC-V1-37。

### Q41 · P2 · 使用 AI 辅助以后，你个人负责什么？距离生产系统还缺什么？

**回答要点：** 准确说明 AI 实际参与过的需求整理、代码草稿、测试候选或文档工作，只说发生过的事。个人需要能解释账户政策、事务与锁序、失败窗口、独立验证和取舍，并审查生成内容。当前只有设计，不能宣称已实现代码或线上经验。即使测试全部通过，仍有真实业务量、合规评审、密钥运维、银行集成、值班和灾难恢复等生产验证差距；本期不承诺填平这些差距。

**追问：** 不看生成代码，能否画出超时与退款的状态/事务图并指出反例？能否解释一次自己否决的建议？没有真实案例时明确说尚未发生。

**工程证据：** PR00～PR20 的实际评审记录、自己能复算的案例、真实修复提交和证据映射；AC-V1-38，不记录未发生的贡献比例。

## 十一、三道白板练习

这些练习检验推理与证据边界，不属于已经执行过的实验。可先遮住“参考推演”，给自己 8～12 分钟作答。

### 白板 A：受理后丢响应，重启时幂等记录已经过期

**题目：** 本地事务提交订单 O、Payment P、Operation X、Provider key K 和待执行任务。Provider 受理 X 后响应丢失，本地没有外部 ID。Worker 崩溃，重启时 Provider 的 K 已被清理。画出恢复路径，说明数据库、API 页面和 Worker 各做什么；再加入一个已过期 Worker 的迟到响应。

**应画出的对象：** order、payment、provider_operation、recovery_task、provider_observation、scope、lease token、唯一键和事务边界。

**参考推演：**

1. P/X 仍可定位，状态为待调查；页面显示结果确认中，不能引导创建新支付。
2. 新 Worker 重领任务但不盲目发送 K。依据持久关联及外部扫描调查；证据不足时挂起升级。
3. 查到可信 Session/PI/Charge 后核对订单、scope、金额和成功 capture，再完成原 P；财务证据仍单独等待 BT。
4. 迟到 Worker 不能用旧 token 覆盖当前状态；其响应可进入观察层，经同一规范化管道检查。
5. 模拟器清理 K 后，错误重发应能够产生第二次外部效果；正确实现必须挡住它，不能让 Fake 永久去重替本地掩盖错误。

**自检：** 能解释“数据库里只有一个 P”和“外部只发生一次资金效果”为什么不是同一个证明；能指出需要人工介入的分支。对应 Q09～Q12、AC-V1-08/09/10。

### 白板 B：退款预占、分润撤回、失败恢复与争议冻结

**题目：** USD cents，C=10000、R=0、H=0，收款 BT 已入账，合作方比例 20%，有效计提 S=2000。申请退款 4000，随后分别推演成功和失败两条互斥分支；成功分支再发生未决争议，最终胜诉且资金证据齐备。写出 C/R/H、cap、S 和分润分录，不需要猜测争议费用。

| 步骤 | R | H | cap | S 应到达的值 | 分润账务动作 |
|---|---:|---:|---:|---:|---|
| 初始 | 0 | 0 | 2000 | 2000 | 已有借分润费用、贷合作方应付 2000 |
| 预占 4000 | 0 | 4000 | 1200 | 1200 | 借合作方应付、贷分润费用 800 |
| 分支一：退款成功 | 4000 | 0 | 1200 | 1200 | 无第二次同额撤回 |
| 分支二：明确退款失败 | 0 | 0 | 2000 | 2000 | 借分润费用、贷合作方应付 800 |
| 在分支一上出现阻断争议 | 4000 | 0 | 0 | 0 | 撤回剩余 1200 |
| 胜诉、资金证据齐备、无其他阻断 | 4000 | 0 | 1200 | 1200 | 按稳定恢复事件重新计提 1200 |

**参考推演：** 分支一和分支二不能同时累计。退款预占变化与必要分润 Journal/allocation 同事务；UNKNOWN 时停留 H=4000，不能套用失败分支。PSP 变化按退款/争议实际 BT 单独入账。争议冻结是本项目整笔基数归零政策，不以 `R + 争议金额` 替代 C/R/H。

**自检：** 对每个步骤重复投递一次，结果不应再变；能说明为什么 S 既要不超过 cap，也要有历史 Journal 支持。对应 Q18～Q23、AC-V1-19～23/25。

### 白板 C：总账与导入一致，但仍漏了一笔退款

**题目：** 同账户/币种/余额域、同截止点、期初为 0。外部独立数据有 Charge net=9700、Refund net=-4000、Payout net=-5700，外部期末为 0。本地导入遗漏退款，且账本也因此没有记退款。银行完整记录显示该 payout 只到账 5500，未提供银行费证据。说明三层对账结果和后续动作。

**参考推演：**

1. 错误本地 BT 求和与账本都为 `9700 - 5700 = 4000`，相互一致仍然不能 PASS；独立期末是 0，残差 4000。
2. 按窗口与 source 追查覆盖，找回退款事实。核对范围和业务归属后走同一个去重/入账入口，本地 PSP 应变为 0。
3. Payout 本金 5700，银行确认 5500，尚未解释 200；在完整证据与匹配条件成立的前提下形成 SHORT_RECEIPT，不能默认把 200 记费用平账。
4. 若后续取得独立银行费 200 的有效证据，则通过有证据、获授权的会计处理解释剩余在途；同笔费用不能重复记。若并非完整银行记录，则此前结论只能 WAITING_EVIDENCE。
5. 展示修复前后 runId、高水位、证据版本、Journal 和残差；不能修改旧 Journal 或只把工单状态改成 RESOLVED。

**自检：** 能分清“导入覆盖”“内部记账”“独立期末”“银行本金分配”四个问题，不将 payout 负 BT 在自动批次汇总中再次重复扣减。对应 Q29～Q34、AC-V1-16/26/27/29。

## 十二、十分钟演示路线

这是一条**实现并验证之后**使用的路线；当前不可展示的环节应说“计划/未验证”，可用白板解释设计。只有真实生成过的证据才能称为演示结果。演示前准备专用测试数据和已核验命令，不现场运行破坏性清库步骤。

| 时间 | 展示内容 | 必须说清的证据和限制 |
|---|---|---|
| 0:00～1:00 | 业务问题、架构与范围 | 从零工程；当前 commit 与版本；一个实际测试账户；哪些是 Fake、真实 Stripe、模拟银行 |
| 1:00～2:00 | 一笔受控商品订单与托管 Checkout | 可信金额、下单政策快照、Session/PI/Charge 关联；创建 Session 不等于扣款 |
| 2:00～3:15 | 受理后超时与原操作恢复 | UNKNOWN 页面、操作键、租约、恢复证据、独立资金效果计数；该故障若用 Fake 必须标注 |
| 3:15～4:30 | 收款 BT 到达与不可变 Journal | amount/fee/net、PENDING_EVIDENCE→已入账、语义去重；重复观察不新增 Journal 或投影 |
| 4:30～5:45 | 4000 退款预占与 20% 分润 | C/R/H、cap 2000→1200、撤回 800、追加凭证；UNKNOWN 保留额度 |
| 5:45～7:00 | 独立期末证据发现漏账 | 固定截止、完整度、漏退款导致残差、回补后的新运行；不使用同一错输入自证 |
| 7:00～8:00 | Payout 与模拟银行匹配 | paid 与 MATCHED 分开；在途、到账、返还互不重复；展示一条证据不足或短款案例 |
| 8:00～9:00 | 测试与夜间报告 | 七项检查、一个 shrink 反例或真实通过报告、八类故障索引；mutation 单列 |
| 9:00～10:00 | 性能/恢复证据与未完成范围 | 有报告才报数字；模拟负载非 Stripe 生产吞吐；银行直连、生产资金、合作方实付仍在范围外 |

## 十三、复习与证据维护

按交付阶段复习：PR00～PR06 对应 Q01～Q09；PR07～PR10 对应 Q10～Q17、Q24～Q31；PR11～PR14 对应 Q18～Q28、Q33～Q34；PR15～PR20 对应 Q31～Q41。跨 PR 的题需要完整集成后才能升级为“已验证”。例如：PR09 的通知能力由 PR11 接入退款，PR12 的分润能力由 PR13 接入争议，不能把预留接口当成闭环。

每次真实运行后，为相关题补充一条证据索引：`题号 / AC-V1 编号 / commit / 环境 / 命令 / 结果 / 证据位置 / 限制`。记录可以是 PASS、FAIL、INCOMPLETE、BLOCKED 或 NOT RUN，但必须沿用[验收文档](payment-platform-acceptance.md)中对不同对象的状态定义；例如对账缺证据用 INCOMPLETE，未执行测试用 NOT RUN，不能互相代替。

练习时先给出结论，再解释一个正常路径和一个反例，最后说明能展示什么。无法用数字复算、指出事务提交点或说清证据来源的部分，回到对应 PR 学习和验证，不用背诵更多术语弥补。
