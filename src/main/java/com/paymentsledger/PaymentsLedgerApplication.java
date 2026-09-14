package com.paymentsledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 模块化单体入口。同一份构建产物以不同运行角色启动：
 *
 * <pre>
 *   --spring.profiles.active=api      HTTP 业务入口
 *   --spring.profiles.active=worker   后台执行与恢复，不暴露业务写入口
 * </pre>
 *
 * 两种角色共享同一个 PostgreSQL 事务域。运行角色的校验见
 * {@link com.paymentsledger.config.RunRoleConfig}。
 *
 * @see <a href="../../../../../docs/adr/0001-modular-monolith-api-worker.md">ADR-0001</a>
 */
@SpringBootApplication
public class PaymentsLedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentsLedgerApplication.class, args);
    }
}
