package com.paymentsledger.config;

/**
 * 运行角色。同一代码库、同一事务域，按角色分别启动进程（ADR-0001）。
 */
public enum RunRole {

    /** HTTP 业务入口。只做短事务；外部网络调用不在数据库事务内。 */
    API("api"),

    /** 后台执行、租约与恢复。提供受控健康检查，不暴露业务写入口。 */
    WORKER("worker");

    private final String profile;

    RunRole(String profile) {
        this.profile = profile;
    }

    public String profile() {
        return profile;
    }
}
