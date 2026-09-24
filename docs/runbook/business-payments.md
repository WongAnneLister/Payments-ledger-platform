# PR06 业务支付：创建、查询与取消

这一步把 PR02 的可信身份、受控价格和 PR05 的 Provider 端口接到业务 API。它不提供退款、业务 Webhook Inbox、商户通知或自动记账；对应后续 PR07–PR12。创建 Checkout 只表示收银台入口已就绪，不表示已扣款。

## 配置与入口

正常 API 仍以 `api` profile 启动。选择一种 Provider：

- 真实测试账户：启用 `platform.stripe.enabled=true`，通过私有环境提供原有 `STRIPE_API_KEY`、账户 ID 和组织/环境/Provider 绑定。数据库中对应的 `provider_account` 必须为同一个 STRIPE test 账户；凭据不能由请求字段选择。
- 本地模拟：使用 `api,test` profiles，显式设置 `platform.provider.fake.enabled=true` 及目录、seed、initial-time、retention。Fake 仅在 test profile 提供，不能与 Stripe 同时启用。
- 未配置 Provider 时，应用仍可启动；支付创建返回受控不可用，不会静默使用模拟器。

PR02 的本地 fixture 绑定的是 FAKE，不可把配置文件中的 Stripe 开关当作数据库账户切换。真实契约测试为明确绑定的测试账户建立**独立的一次性数据库**，不修改日常数据库。

API 提供 `/payment-test.html`：输入项目应用凭据（不是 Stripe 密钥），填写受控商品和价格版本，创建订单后点击托管收银台链接；返回后重新输入应用凭据并查询。页面不保存应用凭据，只在当前标签页保存订单、请求键、请求参数和已知支付 ID。网络结果不确定时刷新仍恢复原请求，不能自动换键重付。明确的请求/金额校验拒绝允许修正表单。Worker 不提供该页面。

## HTTP 契约

| 操作 | 权限 | 行为 |
| --- | --- | --- |
| `POST /v1/payments` | `payments:write` | 必需 `Idempotency-Key`；金额与商品价格×数量一致；201 返回已知 Checkout；同键同内容 200 + `Idempotent-Replay: true` |
| `GET /v1/payments/{paymentId}` | `payments:read` | 可信范围内读取，并对已有外部 ID 作权威查询；返回四维状态、确认时间和全部已观察 attempts |
| `POST /v1/payments/{paymentId}/cancel` | `payments:write` | 必需独立的请求键；先保存取消意图，再查询和按条件 expire；成功 capture 返回 409 |

对外 ID 遵守冻结格式 `pay_<ULID>`，订单为 `bor_<ULID>`；数据库用 UUID 类型保存同一组 128 位。不要用数据库 UUID 文本替代接口 ID。

请求例子（以当前应用实际启用的价格为准）：

```json
{
  "orderKey": "merchant-order-0001",
  "productRef": "demo-product",
  "priceVersion": 1,
  "amountMinor": 10000,
  "currency": "USD",
  "quantity": 1,
  "successUrl": "https://merchant.example/payment-test.html",
  "cancelUrl": "https://merchant.example/payment-test.html"
}
```

`amountMinor` 必须是精确整数，JPY 不做百分之一单位转换；乘法溢出被拒绝。数量 1–10000，返回地址必须是 HTTPS 且不含用户名/密码。未知字段、浮点/字符串金额和任意归属字段不被接受。业务 metadata 最多 44 项，另留 6 项可信映射给系统；标记同时显式写入 Checkout 和 PI metadata，不依赖自动继承。

同一 API 键不同语义内容为 `409 IDEMPOTENCY_CONFLICT`。同一业务订单更换 API 键为 `409 ORDER_INTENT_CONFLICT`，不建立第二笔支付。幂等记录和政策快照存在 PostgreSQL；价格或政策后来改变，不会改变已接受订单，也不会让原请求重放重新计价。

新建请求结果未知时，错误码 `PROVIDER_UNKNOWN_RESULT` 的 `retryable=false`；错误详情 `field=paymentId` 的 `issue` 给出既有资源 ID。保留原请求身份，查询原支付，不换键。若连 HTTP 响应都丢失，用**相同键、相同内容**获取原资源。首次响应 201 不代表 capture；回跳地址、客户点击“取消”和本地超时也不代表资金事实。

Provider 明确拒绝且能够证明没有产生外部效果时，操作记录为 `ACKNOWLEDGED/KNOWN`，支付为 `CANCELED/KNOWN`。首次请求返回带既有 `paymentId` 的 `VALIDATION_ERROR`；相同请求重放返回该资源，不再次发送。页面只有在错误不含既有支付 ID、属于提交前校验失败时才释放表单；已经建立支付的拒绝仍保留原请求，便于查询。

## 持久执行与恢复边界

订单、支付、API 幂等记录、Provider 操作、`recovery_task` 在一个短事务中提交；Worker/同步执行器领取后，在事务外调用 Provider，最后条件回写。Provider 响应另存受控字段的不可变观察，旧执行者的返回也保留取证锚点。

Worker 使用 `worker` profile，默认每 5000 毫秒扫描一次 PREPARED 队列，每轮最多 25 项。`platform.payments.worker.enabled=false` 可以关闭扫描；轮询间隔为 `platform.payments.worker.poll-ms`。API 提交后执行前崩溃，另一进程仍可从数据库找到 PREPARED 任务。

**PR06 不盲目重发 UNKNOWN 或遗留 SENDING。** 这些状态和执行义务保留，PR07 再实现租约到期重领、L1/L2、保留窗口判断与调查升级；此处不能声称 UNKNOWN 已自动恢复。已有 Session ID 的查询可以取得支付事实，但不是完整恢复框架。

取消只使用 Checkout expire，不调用通用 PI cancel，不触发退款。Session 不再开放、PI 处理中、capture 竞争或信息不完整时，保留 UNKNOWN/CONFLICT。已确认成功的 capture 不被后来失败的 attempt 或取消意图抹掉。出现有效 capture 后，账务状态仍为 `PENDING_EVIDENCE`，PR10 接入正式过账前不产生 Journal。

## 验证与证据

普通 `./mvnw -B verify` 使用隔离 PostgreSQL 与明确标注的 Fake Provider，覆盖并发、提交原子性、价格快照、范围隔离、未知结果和取消竞争。额外的 `PaymentRealContractIT` 仅在 `PR06_STRIPE_CONTRACT=true` 时运行，经过认证业务 API 创建、查询、重放并过期一笔真实 Stripe 测试 Checkout。该实测不提交银行卡、不扣款；PR05 的已付款/退款实测是独立历史证据。

真实测试的允许运行方式为，在私有环境中加载上述 Stripe test 配置后：

```sh
PR06_STRIPE_CONTRACT=true ./mvnw -B -Dtest=PaymentRequestsTest,PaymentControllerTest -Dit.test=PaymentRealContractIT verify
```

Maven 不自动读取 `.env`，不要把密钥写在命令行或打印环境。真实测试生成 `target/pr06-real/<run-id>/evidence.json`；该目录的 `state.local.json` 与 `operations.local.json` 包含恢复所需的完整引用，只能留在本地。只归档脱敏 evidence 和文本测试报告，不能复制 XML 中的系统属性或密钥文件。未知创建结果需要结合持久操作与 Stripe 请求日志排查，不直接重跑来创建新订单。
