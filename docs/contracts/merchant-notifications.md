# 商户通知契约（PR09 / v1）

平台将确认的支付变化保存为事件，经 Kafka 和持久通知任务投递给服务端配置的商户地址。通知至少投递一次。商户只有将自己的收件记录、业务履约效果同事务持久化，并按业务键去重，才能在响应丢失后仍保持一次履约。

## 载荷

Content-Type 为 application/json，UTF-8，最多 16 KiB。事件持久化后保存原始字节，重投保持 eventId、businessKey、body 不变。不要重新排版 JSON 后验签。

| 字段 | 类型 / 含义 |
|---|---|
| schemaVersion | 整数，固定 1 |
| eventId | UUID，平台事件身份 |
| eventType | payment.succeeded / payment.canceled / payment.state_changed |
| organizationId, environmentId, applicationId, providerAccountId | UUID，事件所属范围 |
| paymentId, orderId | UUID，付款与原订单身份 |
| orderKey | 下单时的订单业务键 |
| resourceVersion | 支付资源版本 |
| currency | 3 字母币种 |
| amountMinor, capturedAmountMinor | 最小货币单位整数 |
| paymentStatus | 平台付款状态 |
| origin | NATIVE / RECONCILIATION |
| occurredAt | ISO-8601 时间 |
| businessKey | organizationId:environmentId:applicationId:orderId:eventType |

现有支付模型可能在部分扣款时已标记 `paymentStatus=SUCCEEDED`；此时只生成 `payment.state_changed`。只有确认全额 capture 才首次生成不可变的 `payment.succeeded`，避免部分金额提前冻结成功通知。

成功履约键由原订单和成功事件类型决定，不依赖发送次数、eventId 或资源版本。metadata、客户资料、PSP 原始对象、通知目标和密钥不进入载荷。现阶段成功通知只放行有效 NATIVE 原订单的全额成功付款；缺资格、资格失效或 RECONCILIATION 来源均保留 HELD，后续 PR15 接入回补资格规则。退款通知由 PR11 接入。

## 内部 Kafka 消息契约

默认 topic 为 `payment-notifications-v1`（部署属性 `platform.messaging.topic`）；record key 为上表的 UTF-8 `businessKey`，record value 为 Outbox 保存的原始 UTF-8 JSON 字节，禁止发送方重建或重新序列化。消息体与商户 HTTP body 相同，16 KiB 上限；Kafka 消息不附商户签名或通知目标。固定消费者身份和 group 为 `merchant-notifications-v1`。

Publisher 取得并提交发布租约后发送，broker 确认之后才按租约 token 标记 PUBLISHED；在两者之间退出会再次发送同 eventId/body。生产者启用幂等、acks=all，这不能替代数据库 Outbox/Inbox 去重，也不提供跨 PostgreSQL 和 Kafka 的原子提交。

Outbox 只允许受控 `write_payment_notification` 函数从支付和原订单生成；运行角色没有原始 INSERT 权限，不能把关系字段写成状态变化、却在载荷中伪造成功事件。函数固定 search_path，仅授予运行角色必要的执行权限。

消费者不信任 Kafka 中的身份声明：解析 eventId 后读取平台权威 Outbox，必须与其完整原始 body 逐字节一致；scope、资源、目标都不能由外部消息重定义。不存在的事件、非法 body、内容冲突在 `notification_rejection` 中持久保存摘要和受控原因后才允许 ack，不保存恶意原文。数据库失败时不得 ack。

按 `(consumer_name,event_id)` 唯一写入 Consumer Inbox，并在同一事务创建 delivery_task；目标配置存在时同时冻结 URL/keyRef/version。重复消息不再创建任务。该本地事务提交后才确认 Kafka offset（关闭 auto commit，使用 MANUAL_IMMEDIATE）；提交后确认前退出会重投，已提交的任务仍只有一条。Kafka 认证、topic ACL 和 retention 由部署管理者配置，不能用可伪造的消息 key 作为租户授权凭据。

## 签名

每次请求包含四个单值头：

- `X-Payments-Event-Id`：eventId。
- `X-Payments-Business-Key`：businessKey。
- `X-Payments-Timestamp`：本次发送的 Unix 秒数。
- `X-Payments-Signature`：`v1=` 加小写 64 位十六进制 HMAC-SHA256。

签名输入为 UTF-8 编码的 `timestamp.eventId.businessKey.`，紧接原始 body 字节。密钥至少 32 字节；商户与平台通过部署配置共享密钥，数据库仅保存 keyRef。接收方接受时钟误差 ±300 秒，以常量时间比较签名，校验 body 中 eventId/businessKey 与头部一致，再执行去重事务。重试产生新时间戳和签名，不改变事件。

## 商户处理顺序

1. 限制 body 大小，验证签名、时间和身份。
2. 在商户自己的数据库事务中按 eventId 插入收件记录，保存摘要。同 eventId 不同摘要返回 409；相同事件视为重复。
3. 对 payment.succeeded 校验付款成功、原订单及金额，按 businessKey 唯一约束插入履约效果；重复成功事件不重复发货。
4. 提交事务后返回 2xx。若提交后连接断开，平台会重投，商户去重仍须有效。

2xx 表示平台投递完成；408、429、5xx 和网络失败退避重试；其他状态（包括重定向）进入 DEAD。缺配置/资格进入 HELD。平台不读取或保存远端响应正文。HTTP 不在平台数据库事务内执行。

## 地址边界

仅管理员配置的 org/environment/application 目标可用；支付请求和消息均不能指定回调 URL。生产只允许 allowlist 内的 HTTPS 主机、443 端口，无 userinfo/query/fragment，DNS 全部地址必须为公网。验证后的地址通过 HttpClient DnsResolver 固定到该次连接，保留 TLS 主机校验；禁用重定向、自动 HTTP 重试和系统代理。实现依据 [Apache HttpClient DnsResolver API](https://hc.apache.org/components/httpcomponents-client-5.5.x/5.5.2/httpclient5/apidocs/org/apache/hc/client5/http/DnsResolver.html)。

目标 URL/keyRef/version 在通知任务中冻结；更改或停用目标后旧任务会暂停，不会自动改投新地址。独立本地模拟商户仅通过代码 fixture 工厂允许精确的 127.0.0.1 HTTP 地址，生产没有允许任意内网的开关。

特殊地址分类参考 [IANA IPv4 特殊用途登记](https://www.iana.org/assignments/iana-ipv4-special-registry/)和 [IANA IPv6 特殊用途登记](https://www.iana.org/assignments/iana-ipv6-special-registry/)；除私网外还拒绝文档、基准测试、过渡与保留地址。DNS 最长等待 2 秒，HTTP 请求有 10 秒总截止时间，另设连接/握手 3 秒与读取 5 秒超时。
