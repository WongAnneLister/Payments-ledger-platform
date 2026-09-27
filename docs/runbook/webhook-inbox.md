# PR08 Stripe Webhook Inbox

本接口接收并保存 Stripe 事件，后台再处理支付事实。HTTP 200 表示事件已经可靠保存或命中同一事件重放，不表示支付成功、账务完成或已通知商户。

## 配置入口

使用 `api` profile，并显式设置 `STRIPE_WEBHOOK_ENABLED=true`（对应 `platform.webhooks.stripe.enabled`）。默认关闭时请求返回 503，不假装已经接收。`worker` profile 不暴露此 HTTP 路由。

| Spring 属性 | 环境变量后备值 | 内容 |
| --- | --- | --- |
| `platform.stripe.webhook-secret` | `STRIPE_WEBHOOK_SECRET` | 该 endpoint 的 `whsec_` 签名 secret，不是 API 私钥 |
| `platform.stripe.account-id` | `STRIPE_ACCOUNT_ID` | 可信的 Stripe account ID |
| `platform.stripe.organization-id` | `STRIPE_ORGANIZATION_ID` | 数据库组织 UUID |
| `platform.stripe.environment-id` | `STRIPE_ENVIRONMENT_ID` | 数据库测试环境 UUID |
| `platform.stripe.provider-account-id` | `STRIPE_PROVIDER_ACCOUNT_ID` | 数据库 Provider 账户 UUID |
| `platform.stripe.webhook-api-version` | `STRIPE_WEBHOOK_API_VERSION` | 冻结的 `2026-07-29.dahlia` |
| `platform.webhooks.stripe.max-body-bytes` | 无 | 默认 262144，允许 1 至 1048576 字节 |
| `platform.webhooks.stripe.signature-tolerance` | 无 | 默认 `PT5M`，签名时间前后双向检查 |

签名接收可独立启用，无需为接收过程调用 Stripe API。账户必须在数据库中注册为 ACTIVE、STRIPE、测试模式，组织及环境也必须 ACTIVE，account ref 与部署绑定完全相同。主动查询/恢复仍需另外启用真实 Stripe 适配器并提供其配置；仅设置本地参数不代表远端 endpoint 已配置成功。

V6 保留既有支付与恢复任务，并把旧查询确认时间带入新的来源优先级判断。同一组织、环境和 Provider 账户内，一个 PaymentIntent 只能关联一笔支付；若升级前已有重复关联，迁移会失败并回滚，不删除旧数据或任选一笔支付。修复冲突证据后才能重试迁移。

API 和 Worker 必须连接同一 PostgreSQL，使用应用 runtime role；迁移使用独立的迁移角色。Spring Boot 不会自动读取 `.env`，启动前按既有方式导出所需变量，不能把密钥写入命令日志或版本库。

## 接收与保存

`POST /webhooks/stripe` 使用 `Content-Type: application/json` 和单个 `Stripe-Signature` 头，无需 Bearer。原始字节先验签，再解析；代理必须保留请求体字节。事件中的 account、livemode、API version 必须符合固定 endpoint，metadata 不能切换身份。

- 200：Inbox 已提交，或相同范围的已提交 event 重放；响应 `received=true`，`duplicate` 区分两者。
- 400：签名缺失/错误/过期、字节篡改、版本/范围不匹配、请求超限等；不产生业务或账务效果。
- 409：同 eventId 的持久证据冲突，不能覆盖原件。
- 503：入口未配置、账户绑定不可用或数据库写入失败；可重试，不能被代理改写为 200。

数据库只保存事件身份、原始字节 SHA-256、验签/版本/时间信息和必要白名单字段。客户联系方式、任意 metadata、client_secret、签名 header 和 secret 不保存；关联 metadata 仅保留 `payment_id`。原始字节完成验签和摘要后不长期保留。退款、争议和 payout 保留必要对象关系以供后续重新查询，不能据此宣称其业务已经处理。

## 业务生效

传输去重使用 ProviderScope + eventId；规范事实按外部对象和事实类型去重。重复事件、不同 eventId 描述同一 capture、以及 Webhook 与主动查询看到同一 capture，都不应重复累计付款金额。金额/币种或归属冲突保留为待调查证据，不能通过换语义键再应用一次。

当前支付处理器接收 Session 的 completed/expired/async_payment_succeeded/async_payment_failed，PI 的 created/succeeded/processing/payment_failed/requires_action/canceled/amount_capturable_updated，以及 Charge 的 succeeded/failed/pending/captured。其他事件（包括 `charge.refunded`）保留为后续处理等待，不返回业务已完成的结论。

事件不保证先后顺序。Charge 先到而 PI/支付还无法关联时进入持久依赖等待；依赖补齐后重试同一记录。处理失败、进程退出或租约过期也保留重领资格。Worker 处理事务遵守 payment 在前、Inbox 完成锁在后的固定顺序；旧 token 不能完成新租约。

Worker 默认每 5000 ms 调用一次处理器，每批最多 25 条；可通过 `platform.webhooks.worker.enabled=false` 关闭调度，`platform.webhooks.worker.poll-ms` 调整间隔。领取租约为 30 秒；缺依赖或失败后从 5 秒起指数退避，最高 300 秒。等待记录保留首次见到的时间，依赖满足后记录解决时间。

成功 capture 不被晚到失败尝试或旧状态抹掉。Session completed、PI succeeded 和客户回跳本身不足以证明完整资金事实；未付或 expired 的单个 Webhook 也不证明不存在其他已受理付款。成功支付缺少 BT 时保持 `financialStatus=PENDING_EVIDENCE`，PR08 不写 Journal。

## 后续边界

PR09 接商户通知/Outbox；PR10 完成 BT 导入、财务事实去重及入账；PR11/13/14 分别接退款、争议、payout；PR15 处理外部枚举和人工核实。收到这些资源的事件与完成这些资源业务是两回事。

本地验证使用真实 PostgreSQL 和本地 HMAC 签名 fixture，签名由真实 Stripe SDK verifier 验证；它不代表本次已完成真实 Stripe endpoint 投递实验。验证结果及未运行项见 [PR08 证据](../evidence/pr08/README.md)。
