# PR03 不可变账本实现设计

范围依据 PR03 提示词、ADR-0004/0007/0008 与账本科目契约。只实现本地账本核心，不增加公共任意入账接口，不调用 Stripe，不实现 BT/退款/分润业务。

## 受控过账

选择 Java 服务调用一个 PostgreSQL SECURITY DEFINER 过账函数，函数固定 search_path，撤销 PUBLIC 执行权限。相比纯 Java 多语句更新，这样运行角色不能绕过过账路径直接改投影；相比只靠触发器自动记余额，唯一过账入口更明确。

V3 新增 ledger_account、financial_event、journal、journal_entry、balance_projection。应用角色 SELECT/INSERT 事件与凭证/分录，但无 UPDATE/DELETE/TRUNCATE；投影只有 SELECT。配置科目通过迁移/fixture 管理，不提供公共配置接口。受控函数一次完成事件、凭证、分录、封存和投影。

Journal 只有 POSTED 状态。构建事务 ID 由数据库生成，sealed_at 只允许在同一事务由受控函数从空设置一次。Entry 插入要求当前事务构建且未封存。延迟约束在提交时拒绝未封存、空/单行/失衡/总额溢出的凭证，事件也必须有对应凭证。无外部可见草稿。

事件唯一键为 org/env/event_key；重放同时比较币种、Provider、来源、规则、原始事实 JSON 与规范化分录，同键不同内容返回 EVIDENCE_CONFLICT。新增事件（rank70）后按 PostgreSQL UUID 顺序锁账户（rank80），再更新投影（rank85）。并发重放返回同一个 Journal。READ COMMITTED；服务可加入外层业务事务，绝不内部提交外层业务操作。

科目用复合 FK 绑定 org/env/currency；Provider 类科目只能属于同一个已绑定 Provider/PAYMENTS 域，INTERNAL 和 SIMULATED_BANK 不伪造 Provider。合法凭证可同时包含这些来源类型（如银行现金与出款在途），禁止的是不兼容归属。PSP 净借方余额允许为负。投影采用 signed debit-minus-credit，正常贷方科目显示时取相反符号。

## 服务接口

LedgerPostingService.post 接受明确 LedgerScope 与不可变 PostingCommand；金额为正 long，Java 以 BigInteger 校验合计，数据库再次校验。facts 保留来源属性，重放不只比较总金额。

ReversalService.reverse 读取原凭证后反转所有方向，建立新事件及 reversal_of_journal_id。相同冲正命令幂等；同一原凭证最多一次直接冲正，换键不能重复冲正。数据库验证冲正集合确为原凭证的反向。

JournalQueryService 使用显式组织/环境/币种/Provider 范围查询内部账本，返回不可变完整凭证；不暴露业务应用跨应用的公共账本 API。

BalanceRebuildService 在 REPEATABLE READ 只读事务内直接扫描分录，以独立 BigInteger 算法重建并比较投影，不调用过账函数，不改余额。返回带 runId、模式、快照/时间、范围、结果和逐科目残差的报告。
默认实现 OPENING_JOURNAL，必须显式传入模式；支持受控 EXTERNAL_OPENING_SNAPSHOT 输入，要求证据来源/摘要/cutoff/期初余额，拒绝任何同范围 OPENING Journal 与该模式混用，扫描 strictly after cutoff。外部期初模式只用于有明确期初证据的 fixture，报告不得冒充 Stripe 余额对账。

## 验证

实际只读/受限运行角色 + 隔离 PostgreSQL：合法手算、金额边界、跨范围、提交约束、直接 SQL 改删/补插、并发重放/相反账户顺序、原子回滚、重复及篡改冲正、独立重建/故意投影漂移；空库和 V2→V3 升级迁移。最后全量构建与独立审查，不改已发布 V1/V2。
