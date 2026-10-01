# PR14 Payout 与模拟银行验证

基线 `0770f43ed7ec327a0a03c70d8d9103b1b0f1f87e`，分支 `codex/pr14-payout-bank`。

Java21 执行 `./mvnw -B clean verify`：**532 passed，3 skipped，0 failure/error**。单元测试 196 项、集成测试 336 项通过。默认跳过两个原有真实 Stripe 契约测试和一个新只读 payout probe。新 probe 已在独立 opt-in 执行中通过1项；真实HTTP200、列表0条，**仅列表读取VERIFIED，真实 payout 对象/返还/归属 NOT_RUN**。没有创建主动 payout；该可选写能力 N/A。本期银行全部 **SIMULATED_BANK_EVIDENCE**，不能宣称银行直连或真实到账。

| 验证 | 证据 |
|---|---|
| paid不生银行现金，实际扣减BT才进在途，返还新BT追加记账 | PayoutCoreIT / PayoutLifecycleIT |
| 银行部分到账、多payout合法拆分、独立费用101精确分配2000/3700 | PayoutLifecycleIT |
| 重导/重试幂等、同身份不同数据拒绝、来源行跨文件重复整批回滚 | PayoutLifecycleIT |
| 银行后返还SUSPENSE，显式退回证据后解除资金暂记；返还后银行保留冲突不双算 | PayoutLifecycleIT |
| 状态/BT先后，signed实际PSP费用，不凭状态退款或再次出款 | PayoutCoreIT / PayoutLifecycleIT |
| 双PostgreSQL连接抢同一银行行，累计不超过5700，失败写入整笔回滚 | PayoutLifecycleIT |
| scope/币种/引用/日期检查、缺证据等待、完整范围部分到账短款、越界日期不会误报短款 | PayoutLifecycleIT |
| 查询/两个Worker/Webhook实际调用，ACK回滚，自动归属版本刷新与手动无FIFO | PayoutLifecycleIT |
| immutable金额比较，正常availability刷新，scoped证据FK/自动归属来源约束 | PayoutLifecycleIT / V13 |
| V11→V12与历史起点→V13迁移、重复迁移0、最小运行权限和账本重建 | PayoutSchemaIT及全量历史回归 |

本金手算：转出5700=在途5700+银行0+返还0；正常银行匹配后5700=在途0+银行5700+返还0。独立失败分支5700=在途0+银行0+返还5700。银行已匹配后返还先保留银行5700、本金返还0、SUSPENSE5700；有显式银行退回后净银行0、本金返还5700、SUSPENSE0。费用单列，不占额外本金。fixture从单独outgoing事实开始，PSP净变化初始-5700；这些局部测试不宣称全账户外部余额完整。

银行单行费用101，拆给2000/3700：第一份费用floor(101×2000/5700)=35，第二份66；银行现金1965+3634=5599，确认本金仍5700。实际PSP费用30扣减后净变动-5730；返还本金5700及有证据的费用-30后净变化0。

- [构建结果与源码摘要](verification.json)
- [最终全量输出](build-output.txt)
- [真实只读账户结果](real-payout-read.json)
- [真实只读构建输出](real-read-build.txt)
- [实际 payout 测试用例](payout-test-cases.json)
- [依赖版本](dependencies.json)
- [RED/GREEN](red-green.json)
- [规格与质量审查](review-results.json)
- [运行说明与固定 schema](../../runbook/payout-bank.md)

本期交付本地分支提交；main合并与远端推送由后续用户指令决定。证据不含原始JUnit XML进程属性或账户凭据。
