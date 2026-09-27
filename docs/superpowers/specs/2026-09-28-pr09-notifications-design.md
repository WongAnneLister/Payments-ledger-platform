# PR09 Transactional Outbox、Kafka 与商户通知

基线 main `b276562`。用户要求继续执行已冻结 PR09；依据 PRD FR-PAY-04、AC-V1-14/15 支付部分和 ADR-0007。实现并验证后提交功能分支，不自动合并或 push main。

## 决策与边界

PostgreSQL 保存事件、发布义务、消费身份、通知任务；Kafka 只传递消息。单进程内存事件无法覆盖提交后崩溃；直接在支付事务中发 Kafka/HTTP 会把外部调用与本地锁耦合，因此采用持久 Outbox + 提交后至少一次发送。支付状态变更有多个已有入口：用 V7 支付触发器调用统一数据库写事件函数，使同步结果、查询、恢复、Webhook 全部受同一事务保证；Java OutboxWriter 暴露相同写入器供受控重放，要求调用者已有事务并遵守 payment→outbox 锁序。

本期只接支付。退款、财务 Journal 和 L3 自动回补分别由 PR11、PR10、PR15 接线。通知成功只表示目标返回 2xx；任意第三方是否重复履约依赖其自身持久幂等实现。本地模拟商户用独立数据库验证一次效果。

## 稳定事件与资格

事件类型为 payment.succeeded、payment.canceled，以及非终局的 payment.state_changed。只对 resolution_status=KNOWN 的确认状态生成；UNKNOWN/CONFLICT 不生成可履约成功通知。初始 CREATED 可以产生 state_changed，业务状态不变的重复查单不产生新事件。终局事件以范围+payment+类型唯一，版本和 eventId 固定；非终局确认变更以资源版本去重。

事件 JSON 字段固定：schemaVersion=1、eventId(UUID)、eventType、organizationId、environmentId、applicationId、providerAccountId、paymentId(UUID)、orderId(UUID)、orderKey、resourceVersion、currency、amountMinor、capturedAmountMinor、paymentStatus、origin、occurredAt、businessKey。businessKey 为 org:env:app:orderId:eventType；成功履约键不依赖运输重试、eventId或付款资源版本。body 最大 16 KiB，不含客户资料、metadata、PSP原始对象、目标地址或密钥。持久 text payload 保存精确可重发字节和摘要。

提供 FulfillmentEligibility 接口。成功通知要求同 scope 的唯一原订单、金额/币种一致、SUCCEEDED/KNOWN且全额 capture，以及显式有效的原订单资格。新本地下单在独立资格表记录有效资格；已有订单升级不自动获得历史履约许可，缺记录或无效订单保留 HELD。未来 RECONCILIATION 来源默认不放行，PR15 再接核实政策。资格不修改不可变订单快照。领取通知前重新检查当前资格；资格不符可见且不发请求。

## 持久执行

V7 新增 outbox_event、consumer_inbox、delivery_task、merchant_notification_target、order_fulfillment_eligibility 和必要拒绝/审计字段。范围 FK、不可变身份/载荷、runtime无DELETE。升级不能重写支付/恢复身份，也不生成历史成功通知。

Publisher 短事务领取 token/lease→事务外 Kafka send/确认→token条件完成；发送后丢完成标记允许再次发同event。消费者校验消息与本地权威Outbox身份/载荷、范围，Consumer Inbox去重与 delivery_task 创建在同一事务；提交后 ack。无效消息先持久拒绝证据再 ack，数据库失败不 ack。两个消费者争同event只产生一任务。Kafka监听与发布仅在 worker 且 messaging显式启用时运行。

目的地址按 org/environment/application 配置，URI与keyRef不可来自消息或支付请求。密钥仅由部署配置解析，数据库仅存keyRef。目标快照固定；关闭或更改配置时旧任务必须停止或明确重绑，不能静默改投。delivery task claim与完成短事务，网络在事务外；超时/408/429/5xx退避，永久4xx/重定向和超次数进入可见DEAD，资格或配置缺失为HELD。lease过期可重领，旧token不能完成新任务。至少保留 attempt_count、available_at、last_reason、last_status、first/last时间，增加积压/最老年龄/失败指标和仅含ID的日志。

## HTTP 与模拟商户

MerchantTransport接口固定为 send(Target(URI url,String keyRef), UUID eventId, String businessKey, byte[] payload)→Result(Outcome outcome,int status,String code)，Outcome=DELIVERED/RETRYABLE/PERMANENT_REJECT。实现禁止在活跃数据库事务内运行。

生产只允许HTTPS、可信allowlist主机、443端口、无userinfo/fragment/query；DNS解析结果必须全部为公网地址，连接使用该次验证后的固定地址，保留TLS主机校验，禁止重定向/代理/自动重试，连接与响应超时有界。独立fixture工厂只允许精确配置的loopback HTTP端点，生产配置没有“允许任意私网”开关。

请求头：X-Payments-Event-Id、X-Payments-Business-Key、X-Payments-Timestamp(epoch seconds)、X-Payments-Signature(v1=hex HMAC-SHA256)。签名输入为 timestamp.eventId.businessKey. 后接原始body；每次投递签名绑定当前时间和同一稳定事件。生产不记录签名、secret、body或远端响应内容。

模拟商户只绑定loopback，在自己的数据库将签名验证后的收件身份和成功履约效果同事务提交，再返回2xx。成功履约按稳定businessKey去重；同event改body拒绝；可注入提交后丢HTTP响应以验证重投只发生一次履约。

## 验证

沿用 main 刚完成的309通过/2跳过且源码摘要相符的基线。新增真实PostgreSQL、Kafka 3.9.0 Testcontainers和真实loopback HTTP实验：支付事务回滚、提交后未发布、发送后未标记、消费前/后提交崩溃、broker中断恢复、双消费者、租约过期、重复状态/消息、商户提交后断响应、永久失败/退避、无效目标/DNS/重定向、历史资格拒绝、V6升级无自动履约。真实外部商户与Stripe资金调用不运行，证据标明本地独立模拟商户。

完成独立规格审查→修复复审→独立质量审查→全量clean verify、文档/契约检查、源码hash与非打印秘密扫描，提交 codex/pr09-notifications。
