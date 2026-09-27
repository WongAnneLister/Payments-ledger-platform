# PR09 商户通知运行手册

参考[事件与签名契约](../contracts/merchant-notifications.md)。支付事件、Kafka 消费收件箱与通知任务保存在 PostgreSQL。应用重启后从持久任务恢复；Kafka 和商户 HTTP 都在短事务提交后调用。

## 开启 Worker

默认不连接 Kafka 消费/发布。只在 `worker` profile 设置 `PAYMENT_MESSAGING_ENABLED=true` 后启用本模块。API profile 仅随支付事务写 Outbox，不发送通知。

Kafka 地址通过 `SPRING_KAFKA_BOOTSTRAP_SERVERS` 配置，本地 Compose 使用 `localhost:9092`。生产部署的 TLS/SASL 使用 Spring Kafka 标准部署属性，不把认证内容写进仓库。默认主题 `payment-notifications-v1`（可用 `PAYMENT_NOTIFICATION_TOPIC` 覆盖），固定消费组 `merchant-notifications-v1`。上线前由基础设施管理者创建主题并设置合适的副本、保留与 ACL；不要依赖生产 broker 自动建主题。本地 Compose 可执行：

```bash
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic payment-notifications-v1 --partitions 3 --replication-factor 1
```

通知租约 30 秒，默认最多 8 次 HTTP 尝试，可用 `MERCHANT_NOTIFICATION_MAX_ATTEMPTS` 配置；基础退避 5 秒、最大 1 小时。缺密钥等配置暂停不消耗 HTTP 重试额度。轮询间隔由 `MERCHANT_NOTIFICATION_POLL_MS` 配置，默认 1000ms。Outbox 不因 broker 故障丢弃发布义务，持续退避重试。

Outbox 插入统一经过受控数据库写入函数；运行角色只能更新发布控制字段，不能直接插入、改写或删除事件证据。原订单资格自动生成函数不能由运行角色挂载到其他触发器上；已有订单的资格须由受控配置角色核实。

目标由受控配置角色写入 `platform.merchant_notification_target`，范围为 organization/environment/application，包含 `url`、`key_ref`、`enabled`、`version`。应用运行角色仅能读取配置。填写已核验的 HTTPS 商户 URL，并在 `MERCHANT_NOTIFICATION_ALLOWED_HOSTS` 中列出精确主机名（逗号分隔）；不支持任意后缀匹配。keyRef 只能由字母开头，后接字母、数字、下划线或连字符，最长 64 字符。

密钥使用部署属性 `platform.notifications.signing-keys.<keyRef>`，或环境变量 `MERCHANT_NOTIFICATION_KEY_<KEYREF大写，连字符替换为下划线>`。至少 32 字节。数据库、日志和通知载荷只出现 keyRef，不保存密钥或签名。`.env` 不会由 Spring Boot 自动读取，运行前按已有本地流程导出变量。

## 状态处理

- `READY`：等待执行。
- `RUNNING`：worker 已取得有期限的租约；进程退出后可重新领取。旧 token 不能完成新租约。
- 发送失败后回到 `READY`，按 available_at 退避重试，last_reason 记录失败原因。
- `HELD`：缺目标/密钥、目标改变、原订单不具备履约资格等；修复原因后等待重查。目标版本发生改变的旧任务不会自动改投新目标。
- `DEAD`：永久 HTTP 拒绝或达到最大尝试次数；需要调查与显式处理，不会无限重发。
- `DELIVERED`：商户返回 2xx。它并不能替商户证明业务去重正确。

只查询 event/task ID、status、attempt_count、available_at、last_reason、last_status 等操作字段，避免输出 payload、环境变量、请求头或商户响应正文。排查顺序为 Outbox → consumer_inbox → delivery_task；Kafka offset 仅在收件事务成功提交后确认。拒绝消息保留摘要和原因，数据库失败不确认消息。

缺失签名密钥、资格和目标不是“成功跳过”。任务持续可见；历史订单升级不自动获得资格，也不批量生成历史成功通知。PR15 将定义外部回补的原订单核实规则；本期不能通过直接改 origin 绕过资格。

指标 `payments.notifications.*` 包含 outbox_backlog、delivery_backlog、held、dead、delivered、retry_backlog、delivery_attempts、oldest_age_seconds，记录持久积压、投递次数和最老任务年龄；进程重启不清空这些数据库事实。Actuator 默认仍只公开 health，指标需通过受控监控接入，不能为调试直接公开全部管理端点。

目标改变后旧任务的快照不可覆盖；本期没有自动重绑、重放运营 API（由 PR16 规划）。必须调查原投递是否已产生效果，再由受控运维流程处理，不能靠篡改历史事件解决。

## 本地故障验证

需要 JDK21 和 Docker：

```bash
./mvnw -B -Dtest=NotificationTargetPolicyTest,NotificationSignatureTest,HttpMerchantTransportTest -Dit.test=PaymentOutboxIT,NotificationPipelineIT,NotificationKafkaIT,MerchantDeliveryIT,NotificationMigrationIT,NotificationWorkerWiringIT verify
```

测试启动独立 PostgreSQL 和 Kafka3.9.0 容器，以及只绑定 127.0.0.1 的模拟商户。商户有自己的 PostgreSQL 数据库，先提交收件记录和履约效果，再故意丢一次 HTTP 响应；重试后只保留一次履约。还覆盖发布后未标记、消费提交后未确认、broker 中断、两个消费者、过期租约、永久拒绝、地址和签名限制。

`SimulatedMerchantServer` 是可嵌入的独立测试服务器，不注册为平台业务入口。测试工厂仅接受精确的 loopback 地址，不能通过生产环境变量放宽内网限制。商户 DB 事务与平台 DB 事务不共享连接或事务管理器。

本地实验不调用 Stripe 或真实外部商户。执行结果与版本来源记录在 [PR09 证据](../evidence/pr09/README.md)。
