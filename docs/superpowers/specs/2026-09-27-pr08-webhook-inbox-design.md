# PR08 Webhook Inbox 与统一支付观察

基线 main `e3cac76`。用户明确要求执行已冻结 PR08，范围以 PR08 提示词、PRD、AC-V1-06/07/11/12/13 和 Payment 状态机为准。

## 接收与范围

采用原始字节验签 → 短事务 Inbox → 异步规范观察/支付归并。同步执行所有支付处理会把数据库锁和复杂业务暴露在回调延迟中；只以内存排队会丢失已应答事件。沿用 PostgreSQL 持久任务，不提前引入 PR09 Kafka/Outbox。

`POST /webhooks/stripe` 仅 API role 开放，无 Bearer；默认禁用，由 `platform.webhooks.stripe.enabled` 显式开启。Endpoint 的组织/环境/Provider 账户/Stripe account/API version/签名 secret 来自部署配置，事件 metadata 或 query string 不可选择账户。复用 StripeWebhookVerifier 对原始字节验证；入库事务再次核对 ACTIVE 的 STRIPE 测试账户、组织、环境及精确 account ref。默认最大请求 256 KiB，非法/重复签名头、篡改、错误版本/账户/live、缺签名不写任何表；持久化错误返回可重试 503。已持久的相同范围 eventId 重放返回 200；同 eventId 内容冲突不能覆写原件。

持久接收 API：`WebhookInboxRepository.receive(ProviderScope scope, String accountRef, byte[] raw, ProviderCallResult<VerifiedWebhook> verified)` 返回 `Receipt(UUID inboxId, boolean duplicate)`。该内部入口必须检查 SUCCESS、验证事实和解析头部一致性。HTTP 不执行业务归并。原始字节仅用于当次验签/提取/摘要，数据库保存 raw SHA-256、验签/API version/receivedAt、受控 envelope 和对象白名单字段；不保存原始签名、secret、client_secret、客户联系方式/卡数据或任意 metadata。仅保留 `payment_id` 关联 metadata。对后续资源保留必要对象 ID/关系/状态/金额等以供查询取证，不虚报已经生效。

## 持久执行与归并

V6 新增 webhook_inbox、normalized_observation、dependency_wait，并通过 scope FK、唯一约束、受限 runtime 权限和不可变证据保护身份。Inbox eventId 唯一性包含组织、环境、Provider 账户/livemode。规范语义身份取决于对象/事实类型及受控内容，不能依赖 eventId；不可变 capture 身份按 Charge，金额/币种冲突不能伪装第二次效果。

`WebhookProcessor.processDue(int limit)` 使用短事务领取 Inbox token/lease，事务外解析和准备，在短业务事务中依次锁 payment → provider operation/恢复任务（如需要）→ Inbox/规范观察/依赖，原子记录处理效果和完成状态。过期租约可重领，旧 token 不能完成新处理。异常/依赖缺失持久退避；调度使用注入 Clock，Worker profile 执行。不能在持有 Inbox 锁后反向等待 payment。未持久提交不算处理完成。

Payment/attempt 的归并必须共用 PR07 权威查询路径，定向提取共享观察服务或在现有统一 merge 中接入规范身份；不能 Webhook 和查询各自维护一套状态规则。相同捕获经 Webhook、查询、重复事件只能产生一次业务效果；保留来源证据/最新确认时间。按 ProviderScope 解析 Session/PI/Charge 与本地支付，metadata.payment_id 仅作为经范围/金额/币种/已有关系核对后的候选关联。Charge 先到、PI 未关联时等待；PI/Session 证据补齐后再次处理原等待项。关联冲突必须隔离并保留取证/恢复义务，不任选一个 payment。

成功 capture 为不可抹掉的事实；晚到失败 attempt、旧 Session/PI 状态不能回退 SUCCEEDED。Session completed/PI succeeded 自身不足以替代完整 capture 证据。Webhook 未付/expired 单对象事件不证明无其他资金事实，不直接执行取消。当前 financialStatus 仍为 PENDING_EVIDENCE，0 Journal；BT 正式财务去重由 PR10 完成。退款/争议/payout 进入明确后续处理依赖，不改变支付资金事实。

## 验证与交付

真实 PostgreSQL/runtime role、固定时钟/并发屏障、原始字节本地 HMAC 签名、真实 SDK verifier。覆盖 tamper/invalid/stale signature、错误 scope、DB 故障不假成功、并发 event 重投、不同 event 同 capture、Webhook/查询双入口、三种对象顺序、依赖延迟建立、租约/崩溃、旧状态/冲突、含敏感字段 payload 的白名单保存、未来资源等待、V5 升级及既有回归。签名 fixtures 标记 LOCAL_SIGNED_FIXTURE；不将历史真实签名测试当成本次 HTTP 端到端证据，不调用真实资金写 API。

本地完成实现、独立规格审查再质量审查、全套 verify、OpenAPI/文档检查、证据和 source SHA256 归档并 commit 至 codex/pr08-webhooks。main 合并/push 留待用户明确请求。
