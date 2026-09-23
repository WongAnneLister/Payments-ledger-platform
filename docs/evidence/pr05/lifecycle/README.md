# 真实测试付款、流水、退款与回调（2026-09-23）

本次通过 Stripe **托管 Checkout 页面**提交官方测试卡，完成 USD 1.00 付款，再通过项目适配器全额退款。SDK `33.3.0`，请求及专用 webhook endpoint 版本 `2026-07-29.dahlia`。没有使用直接确认 PaymentIntent 的方式替代 Checkout。

| 核对项 | 实测结果 |
| --- | --- |
| Checkout | `COMPLETE`，`payment_status=paid` |
| PaymentIntent | `succeeded`，USD 100 minor，原始 `capture_method=automatic` |
| Metadata | Session 与 PI 各自显式传入的标记均匹配；未发生顶层标记自动继承 |
| Charge | 1 次 attempt，成功，已捕获 USD 100 minor，关联到该 PI |
| 付款资金流水 | JPY 金额 157，手续费 10，净额 147；source 对应该 Charge |
| Refund | 创建后重新查询为 `succeeded`，USD 100 minor，全额退款 |
| 退款资金流水 | JPY 金额 −157，手续费 0，净额 −157；source 对应该 Refund |
| 真实回调 | 收到下列 6 个事件，全部与本次对象哈希匹配，原始字节验签成功 |
| 篡改检查 | 对每个真实载荷追加一个空格、保留原签名，均 `DEFINITIVE_REJECT` |
| 清理 | 专用临时 webhook endpoint 已删除；本地接收器、隧道、隔离浏览器已关闭；未修改账户原有 endpoint |

付款和结算使用不同币种，因此 USD 100 minor 与 JPY 157 不可直接相减。两条结算流水合计净额为 **−10 JPY**，本次退款流水未退回原支付手续费。本记录只描述这次测试账户的实际返回，不推断其他账户或费率政策。

真实收到的事件：`charge.succeeded`、`payment_intent.succeeded`、`checkout.session.completed`、`refund.created`、`charge.refunded`、`refund.updated`。接收器使用项目 `StripeWebhookVerifier`，在脱敏记录持久化成功后返回 HTTP 200。证据按事件对象哈希匹配本次 Session/PI/Charge/Refund，未把同账户其他事件计入结果。

- [付款、流水与退款查询证据](lifecycle-evidence.json)：请求 ID、对象哈希、金额和逐项断言。
- [真实回调及篡改验证](webhook-evidence.json)：6 个关联事件及验签结论。
- [浏览器提交记录](browser-evidence.json)：实际托管表单、沙盒及金额检查。
- [版本、测试和清理记录](verification.json)：源码哈希、端点创建/删除请求 ID、测试计数。

`./mvnw -B verify`：134 个单元/上下文测试和 45 个集成测试通过，0 失败；默认禁用的旧真实 smoke 测试跳过 1 个。本次真实联调由独立 opt-in probe 执行，证据独立记录，不把它计为 Maven smoke 通过。

**边界：这是 PR05 的真实 Provider 契约验证。** 资金流水是 Stripe Balance Transaction，未写入本项目业务支付表或复式账本。临时接收器没有实现 PR08 的业务 Inbox、去重、状态推进或记账。多次支付尝试、拒付/认证、取消竞争、失败退款、BT 分页和 pending→available、争议、payout、历史报告等未在本次验证，R0 总门槛仍为 BLOCKED。

完整对象 ID、托管链接和稳定操作键保留在被 Git 忽略的 `target/pr05-lifecycle/state.local.json`，不可提交。凭据及 webhook secret 不在本目录。测试卡依据：[Stripe 官方测试说明](https://docs.stripe.com/testing)。
