# PR11 退款运行说明

关联：[退款状态机](../state-machines/refund.md)、[恢复规则](../state-machines/provider-operation.md)、[BT 导入](bt-ingestion.md)、[接口契约](../contracts/openapi.yaml)。

## 使用流程

应用凭据需要 `refunds:write` / `refunds:read`，并绑定原支付所属组织、环境、应用与 Provider 账户。API 角色提供以下入口，Worker 角色运行共享 `OperationRecoveryCoordinator`；无需独立退款 Worker 或第二张恢复任务表。

```http
POST /v1/refunds
Authorization: Bearer <application credential>
Idempotency-Key: refund-order-001
Content-Type: application/json

{
  "paymentId": "pay_01HZX9K2M4N6P8Q0R2S4T6V8W0",
  "amountMinor": 4000,
  "currency": "USD",
  "reason": "REQUESTED_BY_CUSTOMER",
  "clientRefundKey": "refund-order-001"
}
```

`201` 表示预占与执行义务已持久化，响应中的 `Location` 指向退款资源。它不表示 Stripe 已完成退款。相同请求键或 clientRefundKey 重放返回原资源；不同内容返回幂等冲突。`GET /v1/refunds/{id}` 读取状态，跨应用/组织资源返回 404。请求金额是精确正整数，币种必须与原支付一致。

## 如何读状态

业务状态、结果确定性与资金状态分别展示。`RESERVED` 只占用额度，不能减少 PSP；`PENDING`、`REQUIRES_ACTION` 和 `UNKNOWN` 继续保留 H。成功只一次 H 转 R，明确失败/取消只一次释放 H。成功后缺 BT 显示 `PENDING_EVIDENCE`，不能当作账务已经完成。

真实退款扣减即使先于业务成功，也会按可信 Refund→BT 关联入账。失败返还的 `failure_balance_transaction` 是新的余额变动，生成独立 Journal；费用使用真实 BT signed fee。不会按退款比例估算退费，也不会删除原退款分录。这与 [Stripe Refund 对象](https://docs.stripe.com/api/refunds/object) 的失败返还字段语义一致。

`financialStatus=PARTIALLY_POSTED` 表示已知所需流水尚未全部入账；`CONFLICT` 表示退款关联或 BT 事实存在矛盾。已入账 Journal 保持不可变，后到矛盾不会覆写历史。

## 恢复与外部退款

操作领取、租约、账户限流、退避和 L2 扫描复用原支付框架。已知 Refund ID 时查询原对象；无 ID 的未知结果仅在已验证幂等保留窗口内使用原键恢复，超窗保留 H 并升级调查。旧 Worker 返回另存观察，不能覆盖新租约。不要通过换键或改状态释放未知预占。

同一操作的可信观察若出现不同 Refund ID，当前租约持有者会在同一事务内标记退款 `CONFLICT`、冻结控制行并升级调查，完整保留 H。过期租约只能保留观察，不能执行这些状态变更。此时新退款会被拒绝，不能把剩余额度视为安全可退。

退款对象可来自已验签 Webhook、操作查询，或 BT Worker 针对 source refund 的查询。BT 先到且没有回调时，Worker 在数据库事务外查询来源退款，再重放已持久化 BT。未找到可信 Charge/payment 关联的事实保持等待，不伪造订单。

Dashboard 退款保存为外部事实，并冻结 `EXTERNAL_CONFLICT` 控制行，停止新退款。它也会创建共享的 `QUERY_REFUND` 操作和恢复任务；即使终态回调丢失，仍能查询原退款完成收敛，这条恢复路径不会创建新的退款。内部审计解冻入口只接受预期版本及 scoped 观察证据，并要求 H=0、退款均明确结束、重算 R 与控制值一致且不超 C。无法证明本地未知请求与外部退款是否同一笔时保持冻结；PR16 运营流程处理调查，不以猜测释放 H。

## 消息与扩展边界

`refund.succeeded`、`refund.failed`、`refund.canceled` 在状态事务内写入原 Outbox，包含稳定 eventId、refundId、paymentId 与每笔退款的业务去重键；沿用 Kafka、Consumer Inbox、签名商户通知与失败重试。退款通知不触发发货；模拟商户在独立数据库记录一次退款效果后才响应。

PR12 已将预占、成功、失败、取消、恢复与外部冲突接入同一事务，按新额度同步追加分润调整凭证，详见[分润运行说明](revenue-share.md)。`RefundEligibilityPolicy` 已接入 PR13，阻断争议会拒绝新的普通退款。争议与既有或外部退款重叠时保留双方事实并冻结调查，详见[争议运行说明](disputes.md)。

退款事务代码位于 `payment` 包以复用既有操作仓库的事务/租约边界；资金证据适配在 `refund` 包，Webhook 处理在 `webhook` 包。不会新增任意公共过账入口，余额只由 `LedgerPostingService` 更新。

## 验证来源

本期实际执行结果见 [PR11 验证报告](../evidence/pr11/README.md)。PostgreSQL、HTTP、Kafka/通知 fixture 与真实 Stripe 测试分开报告。真实密钥只从本地配置读取，不写入代码、测试报告或聊天。
