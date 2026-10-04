# PR16 运营 API、审批与审计

运营入口只在 `api` profile 提供，使用 `Authorization: OpsBearer ops_<credential-id>.<secret>`。密钥不写入命令历史、日志或证据文件。身份由 PR02 的独立运营凭据解析，组织与环境不能由请求覆盖；跨范围资源统一不可见。

## 角色

| 角色 | 通用查询 | 对账、银行导入、异常动作、重试、申请调整 | 审批 |
|---|---|---|---|
| OPS_VIEWER | 允许 | 拒绝 | 拒绝 |
| OPS_OPERATOR | 允许 | 允许 | 拒绝 |
| OPS_ADMIN | 允许 | 允许 | 拒绝 |
| OPS_REVIEWER | 允许 | 拒绝 | 允许，必须与申请作者不同 |
| PARTNER_VIEWER | 仅自身分润行 | 拒绝 | 拒绝 |

同一人切换成复核员后也不能审批自己的申请。数据库和应用均强制 `created_by <> approved_by`。默认本地 fixture 只有 viewer，不能以其凭据发起写命令；开发审批演示须在受控 fixture/迁移中配置两个独立身份，绝不开放任意身份注册接口。

## 查询

以下路径均从可信运营组织/环境筛选，业务与资金状态分别返回。HTTP 200 表示查询成功，不表示付款成功、银行已到账或对账无差异。

| GET 路径 | 用途 |
|---|---|
| `/ops/payments/{id}` | 订单、Session/PI/Charge、资金事实、凭证与时间线 |
| `/ops/refunds/{id}` | 预占、退款状态、Provider 操作与相关凭证 |
| `/ops/disputes/{id}` | 原始 Provider 状态、处理状态、争议应收/损失及分润阻断 |
| `/ops/payouts/{id}` | 扣减、返还、银行本金分配与短款 |
| `/ops/revenue-shares/{id}` | 政策与分润行；合作方只见自身行 |
| `/ops/ledger/journals` | 按业务单、BT、事件键、时间检索已封存凭证 |
| `/ops/ledger/journals/{id}` | 分录与来源、冲正关联 |
| `/ops/balances` | 币种/科目过滤；`asOf` 为历史截止时间 |
| `/ops/reconciliation-runs/{id}` | 数据完整性、证据版本、七项结果及残差 |
| `/ops/exceptions` | 差错与运营处理状态 |
| `/ops/tasks` | 既有恢复任务、租约、次数与调度时间 |
| `/ops/adjustments/{id}` | 复核前查看范围、作者、分录草案、证据及理由摘要 |

历史余额由截止时间内的不可变分录计算，不把今天的余额投影标成过去的余额。`rebuildMatches` 缺少适用核对时为 null。列表默认 `limit=50`，允许 1–200，使用返回的 `nextCursor` 继续读取；游标不会增加权限。运营资源 ID 支持业务原生前缀 ID 和 UUID，`/v1` 的原有 ID 格式保持不变。

付款、退款、争议和 payout 详情也关联针对该目标已批准的人工调整凭证，便于追溯原事实与后续调整。详情中的有上限证据集合保留最新 100 条；`traceCompleteness=INCOMPLETE` 和 `truncatedSections` 明确指出被截断的集合。该标记只描述追溯数据是否完整，不能替代付款或资金状态。

余额查询没有分页参数：先在 SQL 中应用账户/币种选择条件，符合条件的账户超过 1000 时返回受控错误，要求缩小范围，不会把截断结果冒充完整余额表。

## 写命令

六个 POST 入口均要求稳定的 `Idempotency-Key`。重复同键同内容返回原结果；同键内容改变被拒绝。权限检查和审计在重放时仍然执行，不能用他人的请求键绕过授权。

带 `Idempotent-Replay: true` 的响应是原命令结果，资源可能已经继续推进。当前状态通过对应 GET 查询；重放审计记录本次观察到的当前资源状态，不把原响应状态冒充现状。同一调整目标的 UUID 与前缀 ID 共用幂等命名空间，改变请求中的 ID 写法不会新建第二份申请。

- `/ops/reconciliation-runs`：提交 `runType`、`businessDate`、`currency`、`reason`，可设置 `overlapLookbackDays`。API 只持久化任务，外部查询仍由 Worker 在事务外执行。Worker 必须显式启用相应财务摄入/对账开关，并配置匹配的可信账户/币种/业务时区，详见[对账运行说明](reconciliation.md)。
- `/ops/bank-statements/import`：导入明确标注的模拟银行文件，校验 `fixtureId`、版本、摘要及各行，调用既有导入/匹配服务。重复文件返回原批次，不能重复分配本金。缺少显式完整覆盖区间的请求仅形成不完整证据，不能证明全量银行到账。
- `/ops/exceptions/{id}/actions`：`INVESTIGATE`、`WAIT_EVIDENCE`、`RESOLVE` 或 `REOPEN`。解决必须关联当前范围内可查证的证据，或为该差错批准的调整凭证；同范围内无关的凭证也不能用于解决。运营处理记录与原始对账报告分开，历史报告不重写。最新处理状态按锁内生成的动作序号排序，动作时间在写入时取值，不依赖事务开始时间。
- `/ops/tasks/{id}/retry`：仅唤醒既有恢复义务；不能重置有效租约、已完成任务或通过改键重发过期 UNKNOWN 操作。无法安全重驱时拒绝并保留升级调查状态。来源恢复任务按 Worker 相同的任务→BT 锁顺序执行，避免与正在处理的 Worker 相互等待。
- `/ops/adjustments`：提交目标、理由、证据和借贷平衡分录草案，只生成 `PENDING_APPROVAL` 申请，不记账。
- `/ops/adjustments/{id}/approve`：先通过 GET 详情查看原始草案和证据，再由独立 `OPS_REVIEWER` 提交 `APPROVE` 或 `REJECT`。批准与追加调整凭证处于同一事务；拒绝不记账；并发/重复审批至多产生一张凭证。

草案中的账户必须属于可信组织、环境和同一启用币种。申请不会修改原凭证，审批也不会更新、删除或向已封存凭证补插分录。对账差额不会因人工动作自动清零，后续新版本对账仍读取实际资金证据。

对账详情提供安全的报告证据摘要及 `evidenceRef`，包含 UUID、类型、来源、版本、摘要和时间，不返回原始 payload。差错解决会核对引用与当前 run、原始 BT/来源或该差错的已批准调整之间的关系；其他 run 或同范围内无关的来源仍被拒绝。

例如，在已配置独立身份的本地环境，用运营凭据受理一个对账请求；`OPS_OPERATOR_TOKEN` 由本机环境注入：

```sh
curl --fail-with-body -X POST http://localhost:8080/ops/reconciliation-runs \
  -H "Authorization: OpsBearer $OPS_OPERATOR_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: local-nightly-2026-10-04-v1' \
  --data '{"runType":"NIGHTLY_ALL","businessDate":"2026-10-04","currency":"USD","overlapLookbackDays":1,"reason":"local verification"}'
```

保留返回的 `runId`，用 GET `/ops/reconciliation-runs/{runId}` 查看实际执行、完整性和七项结果。重试受理请求时复用原键和正文；新的业务请求使用新键。人工调整则先 POST 创建草案、GET 查看，再由另一位复核员 POST 审批，不能把创建成功当作已入账。

## 审计与验证边界

所有运营写命令、重放与拒绝保留审计，包括认证、范围和只读权限拒绝。成功动作与业务效果同事务提交，避免业务已成功而审计丢失；失败在回滚后另行记录。审计记录 actor、role、action、target、可信范围、requestId 的 SHA-256、结果、理由/证据摘要及时间。异常动作的 before 状态来自串行化锁内最新处理记录；认证前被拒绝时使用 ANONYMOUS，不猜测归属或身份。原始 token、secret、请求正文和敏感银行说明不写入审计，不能留下半申请或半凭证。

本期不添加主动 Stripe payout、抗辩提交、合作方实付或真实银行直连。Stripe 历史报告能力仍未验证，银行来源仍为 `SIMULATED_BANK_EVIDENCE`。API 受理成功不解除 `UNKNOWN`、`PENDING_EVIDENCE`、`INCOMPLETE` 或冲突。

## 模拟银行摘要计算

`schemaVersion` 使用字符串 `"1"`。`contentDigest` 是外部请求证据文档的 SHA-256：删除运营字段 `reason`、`contentDigest` 和归属断言，按 `lineNo` 升序排列行，递归按对象键排序，以紧凑 UTF-8 JSON 编码。保留所有文件证据字段，包括覆盖区间、银行账户引用、完整性声明和原始 description；description 参与摘要但不写入转换后的银行事实。外部摘要与 PR14 内部转换后的证据摘要分别保存，不能混用。

以下示例对本地请求文件 `bank-import.json` 计算摘要，不读取或输出凭据：

```python
import hashlib, json
from pathlib import Path
body = json.loads(Path("bank-import.json").read_text())
source = {key: value for key, value in body.items() if key not in {
    "reason", "contentDigest", "providerAccountId", "organizationId", "environmentId", "livemode"
}}
source["lines"] = sorted(source["lines"], key=lambda line: line["lineNo"])
encoded = json.dumps(source, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
print(hashlib.sha256(encoded).hexdigest())
```

将输出填入同一请求的 `contentDigest`。文件内的 `bankRef` 是模拟 payout 的稳定 Provider ID；正金额转换为 CREDIT，负金额转换为 RETURN，返还行不允许银行手续费。省略覆盖区间时按行日期推导最小包络，但 `complete` 强制为 false。默认银行账户引用为 `ops-simulated-bank`，可以使用显式受控的 `bankAccountRef`。
