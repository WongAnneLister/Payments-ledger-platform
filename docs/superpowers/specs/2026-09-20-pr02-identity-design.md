# PR02 身份与账户范围

依据已冻结的 ADR-0006、AccountScope 契约及 PR02 执行提示词继续实施；不改变既定业务语义。

## 方案与边界

采用数据库凭据 + bcrypt 摘要 + 每次请求解析的不可变上下文。相较于自签 JWT，数据库查验能立即反映吊销与授权变更；相较于完整 OIDC，本期无需额外身份基础设施。业务使用 Bearer，运营使用 OpsBearer，分别存表。

Token 使用类型前缀、凭据 UUID 和随机 secret；UUID 仅用于索引，必须通过摘要验证。凭据绑定组织、环境、应用与唯一 Provider 账户。允许币种和权限来自数据库，不从请求取值。有效期采用闭开区间，所有父级状态参与认证。认证查询在只读事务内完成。

新增 V2 迁移，不改 V1。组织、环境、应用、Provider、凭据、币种授权、运营身份、受控价格与分润政策使用复合外键约束范围。Provider 外部账户引用全局唯一。金额使用 bigint，配置指数限定 0..4，USD=2、JPY=0；金额换算严格拒绝舍入及溢出。分润 fixture 使用一个合作方和总比例基点，未来快照由 PR06 消费。

API 仅提供 GET /v1/identity、GET /v1/applications/{id}、GET /v1/prices/{id}、GET /ops/identity。资源查询显式携带可信 scope，越权读取返回 404；缺失身份 401、伪造归属 403。过滤器校验 Header/参数及 JSON 请求中的归属断言，原始 Webhook 请求不经过 JSON 解析，也不在 PR02 实现回调。Worker 不注册身份或业务 Controller，健康检查公开。

API 与 Worker 都在启动阶段拒绝 live 配置与 live Stripe 密钥。fixture 仅在显式 local profile + 开关下初始化，要求外部提供随机凭据，不写明文、不打印 token；同凭据重启幂等，不覆盖旧数据。额外组织、JPY、账户均为测试 fixture。

## 验证

单元测试覆盖上下文缺失、权限、金额和请求归属检查。隔离 PostgreSQL 集成测试覆盖认证有效期/吊销、跨组织及同组织跨应用读取、运营凭据分离、数据库跨 scope 写入拒绝、金额/币种约束、live 启动拒绝、空库迁移与重复迁移。新增功能不把 AC-V1-01/02 后续资金场景提前标为通过。
