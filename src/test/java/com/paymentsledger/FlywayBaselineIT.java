package com.paymentsledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 空库迁移验证。
 *
 * <p><b>隔离性</b>：使用 Testcontainers 启动<b>一次性容器</b>与唯一命名的新数据库
 * {@code pr01_isolated_migration_check}。本测试<b>从不</b>连接、修改或删除任何
 * 已有数据库；容器随测试结束销毁（路线图 §5.5：绝不删除已有或未核实数据库）。
 */
@Testcontainers
class FlywayBaselineIT {

    private static final String ISOLATED_DB = "pr01_isolated_migration_check";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName(ISOLATED_DB)
                    .withUsername("pr01_migrator")
                    .withPassword("pr01_migrator_pw");

    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                // 与生产配置保持一致：历史表放在 platform，不放 public。
                // （PostgreSQL 15+ 起 public 不再默认授予 CREATE，迁移角色也非其属主）
                .schemas("platform")
                .defaultSchema("platform")
                .createSchemas(true)
                .cleanDisabled(true)
                .load();
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    @DisplayName("V1 保持无业务表，空库可升级至 V5，重复迁移幂等")
    void migratesEmptyDatabaseThenIsIdempotent() throws Exception {
        // 1) 确认目标库确实是我们新建的隔离库
        assertThat(POSTGRES.getJdbcUrl()).contains(ISOLATED_DB);

        // 2) 空库首次迁移
        Flyway baseline = Flyway.configure().configuration(flyway().getConfiguration()).target("1").load();
        MigrateResult first = baseline.migrate();
        assertThat(first.migrationsExecuted).isEqualTo(1);

        // 3) 二次迁移：幂等，不再执行任何迁移（等价于「第二次启动」）
        MigrateResult second = baseline.migrate();
        assertThat(second.migrationsExecuted).isZero();

        try (Connection conn = connection(); Statement st = conn.createStatement()) {
            // 4) baseline schema 已建立
            try (ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'platform'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }

            // 5) PR01 不得创建业务表
            try (ResultSet rs = st.executeQuery(
                    "SELECT string_agg(table_name, ',') FROM information_schema.tables "
                            + "WHERE table_schema = 'platform' "
                            + "AND table_name IN ('payment','refund','journal','journal_entry',"
                            + "'balance_projection','financial_event','business_order')")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1))
                        .as("PR01 baseline must not create business tables")
                        .isNull();
            }

            // 6) 基线标记写入且只有一行
            try (ResultSet rs = st.executeQuery(
                    "SELECT baseline_pr, count(*) OVER () FROM platform.schema_baseline")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("PR01");
                assertThat(rs.getInt(2)).isEqualTo(1);
            }

            // 7) 回归断言：Flyway 历史表必须落在 platform，不得落在 public。
            //    手动对 Compose 环境启动时发现过此问题（public 在 PG15+ 不授予 CREATE）。
            try (ResultSet rs = st.executeQuery(
                    "SELECT table_schema FROM information_schema.tables "
                            + "WHERE table_name = 'flyway_schema_history'")) {
                assertThat(rs.next()).as("flyway_schema_history must exist").isTrue();
                assertThat(rs.getString(1))
                        .as("history table must live in platform, not public")
                        .isEqualTo("platform");
            }
        }
        assertThat(flyway().migrate().migrationsExecuted).isEqualTo(12);
        assertThat(flyway().migrate().migrationsExecuted).isZero();
    }
}
