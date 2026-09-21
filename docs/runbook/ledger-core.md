# PR03 账本核心

本模块提供内部 Java 服务，不增加公共任意入账 HTTP 接口。当前输入均为本地会计 fixture；不会调用 Stripe，也不会自动把支付状态当作财务事实。

## 过账

`LedgerPostingService.post(LedgerScope, PostingCommand)` 是唯一生产余额写入口。scope 来自可信身份或 Worker 的受控账户配置。事件键由业务事实稳定构造，重试保持原键；`facts` 包含需要保护的来源属性，后续 BT 接线必须包括 amount/fee/net/source 等原始财务属性。

示例是手算的收款 10000、手续费 300、净额 9700，全部为 USD 最小单位整数：

```java
var command = new PostingCommand(
    "fixture:charge:001", "L-CHARGE", "PROVIDER", "fixture_charge_001",
    mapper.createObjectNode().put("amount", 10000).put("fee", 300).put("net", 9700),
    List.of(new PostingLine(pspAccount, Side.DEBIT, 9700),
            new PostingLine(feeAccount, Side.DEBIT, 300),
            new PostingLine(revenueAccount, Side.CREDIT, 10000)),
    null);
UUID journalId = ledgerPostingService.post(scope, command);
```

再次提交相同内容返回原 Journal，不更新投影。同 org/env/event_key 改币种、Provider、来源、facts 或分录会得到 `EVIDENCE_CONFLICT`。分录输入顺序不构成差异；拆分或合并原分录视为证据变化。事件键不包含币种，改币种不能逃过去重。

科目由迁移角色/受控配置预先建立。Provider 类科目必须关联同一 org/env 下的 Provider，余额域固定 PAYMENTS；银行科目为 SIMULATED_BANK，内部分润等为 INTERNAL，后两者不伪造 Provider。合法的银行到账凭证可以同时包含银行现金和 Provider 出款在途。科目用途与来源域有固定 CHECK。

投影使用 `debit - credit`：资产正常为正、收入和负债正常为负。PSP 允许负余额，不能丢弃真实扣减事实。每条金额、单张凭证每侧合计以及累计投影必须在 bigint 范围内；重建使用 BigInteger，独立检验累计结果。

## 一次性构建与不可变性

Java 服务在 READ COMMITTED 事务中调用固定 search_path 的 SECURITY DEFINER 函数 `platform.post_ledger`。该函数按事件（rank70）、账户 UUID 升序（rank80）、投影（rank85）处理，原子完成事件、Journal、Entry、封存和投影。

- Journal 对外只有 POSTED。构建事务 ID 由数据库生成；提交时必须已封存。
- Entry 仅允许在 Journal 未封存的构建事务中插入。过账函数封存后，即使同一事务也不能再补插。
- 延迟约束验证至少两行、金额有效、借贷平衡和合计范围；原始直接 SQL 也不能提交空/单行/失衡/未封存 Journal，或没有 Journal 的已接受事件。
- `payments_app` 可以 SELECT/INSERT 事件、Journal、Entry，但没有 UPDATE/DELETE/TRUNCATE。它不能直接封存、不能改投影、不能配置科目。函数撤销 PUBLIC 执行权限，仅授予运行角色。
- 迁移角色拥有建表与受控函数权限，不能作为运行连接，也不能把运行角色加入迁移角色。超级用户禁用约束/触发器不是系统承诺保护的范围。

过账加入调用方事务。后续步骤失败时整笔回滚；调用方不能捕获 SQL 失败后继续提交部分业务。`40P01`/`40001` 保留给外层事务拥有者，必须重跑完整本地事务；禁止在重试期间重发外部资金请求。后续多事件业务接线必须整体规划 rank70/80 锁序，不能在拿到账本锁后反向取得业务控制锁。

## 冲正与查询

`ReversalService.reverse(scope, journalId, stableEventKey, reason)` 创建新事件、新 Journal，反转原始所有分录。原始凭证保持不变。数据库验证冲正确为原凭证的反向，并限制一个原凭证最多一次直接冲正；重复同一命令返回原冲正结果，换键不能多冲一次。

`JournalQueryService.get(scope, journalId)` 返回指定 org/env/currency/Provider 下的完整不可变凭证；其他范围统一 NOT_FOUND。它是内部接口，不等同于给所有业务应用授予全组织账本查询权。

## 独立余额重建

`BalanceRebuildService.rebuild` 要求显式模式和完整 MoneyScope（org/env/currency，Provider 设为空）；每个科目独立汇总，不把共享内部科目按某个 Provider 截掉一部分。方法启动独立 REPEATABLE READ 只读事务，读取同一 PostgreSQL 快照下的分录和投影。

- `OPENING_JOURNAL`：从零累计全部可见分录；若有 L-OPENING，也只算一次。禁止额外提供快照。
- `EXTERNAL_OPENING_SNAPSHOT`：必须提供已经核对的来源、SHA-256 摘要、cutoff 和完整科目期初集合；只扫描 cutoff 之后的分录。同范围有 L-OPENING 时拒绝。当前只用于受控、无跨 cutoff 未决写入的迁入 fixture；不冒充真实 Stripe 期末证据。

返回可序列化报告包含 runId、规则版本、LOCAL_MODEL 来源标记、范围、模式、起止时间、数据库快照、期初证据信息及逐科目残差。残差为 `投影 - 独立重建值`。发现差异仅报告，不自动修余额、不开平账凭证。调用方负责保存报告；PR15 再接入持久运行记录、夜间检查与运营流程。

## 验证命令

```bash
export JAVA_HOME="$HOME/.jdks/amazon-corretto-21.jdk/Contents/Home"
./mvnw -B verify
python3 scripts/ci/check_openapi.py
python3 scripts/ci/check_doc_links.py
git diff --check
```

`LedgerDatabaseIT` 使用受限运行角色尝试直接 SQL 绕过及验证 V2→V3 升级；`LedgerServiceIT` 验证真实数据库多连接并发、调用方事务回滚、冲正和独立重建；`LedgerRulesTest` 验证 Java 输入边界。所有清理仅在 Testcontainers 新建数据库中执行。
