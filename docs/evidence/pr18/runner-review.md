# PR18 运行器独立审查

审查范围：`scripts/ci/run_pr18.py`、对应 Python 测试、CI 工作流和运行手册。测试模型/驱动器当时仍在实现，不在本轮审批范围；整体 PR18 另行审查。

## 规格审查

首次发现两项阻断：任意一个并发场景即可 PASS；缺生成器版本也可 PASS。均用临时 JSON 重现。新增回归实际得到 4 个断言失败；修复后要求四个不同场景完整出现且生成器版本非空。独立复验 7 项 Python 测试通过，审查结论 Approved。

四个场景固定为 `competing-refunds`、`same-key-refund`、`refund-dispute-share`、`payout-shared-line`。缺少或重复任何一个都不能通过。

## 质量审查

首次发现 P2：语法合法但类型错误的 JSON（例如 summary 是数组）会触发未捕获 AttributeError，导致主程序未写 run-summary，损失退出码与预算证据。新增回归重现 5 个错误；增加显式结构检查，统一作为无效证据 ValueError 处理。主程序回归确认 FAIL summary 仍保留 Maven 退出码、预算、耗时和证据错误。

独立复验 9 项 Python 测试通过，质量审查结论 Approved，无剩余可操作问题。日志中的 child exit17、malformed summary 和 FAIL 均为明确的运行器单元 fixture，不是项目资金测试失败。

本轮两个审查者均只读；未运行 Maven、Docker 或真实账户请求。
