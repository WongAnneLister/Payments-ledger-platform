package com.paymentsledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentsledger.common.error.GlobalExceptionHandler;
import com.paymentsledger.common.request.RequestIdFilter;
import com.paymentsledger.config.RunRole;
import com.paymentsledger.config.RunRoleConfig;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * 最小上下文测试：**不需要 Docker 或数据库**。
 *
 * <p>验证运行角色解析与基础横切组件（Clock、requestId 过滤器、统一错误渲染）确实装配成功。
 * 数据库相关的验证在 {@code FlywayBaselineIT} / {@code ActuatorHealthIT} 中进行。
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
@ActiveProfiles("api")
class ApplicationContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("上下文加载且横切组件已装配")
    void contextLoads() {
        assertThat(context.getBean(Clock.class)).isNotNull();
        assertThat(context.getBean(RequestIdFilter.class)).isNotNull();
        assertThat(context.getBean(GlobalExceptionHandler.class)).isNotNull();
    }

    @Test
    @DisplayName("api profile 下运行角色解析为 API")
    void resolvesApiRunRole() {
        assertThat(context.getBean(RunRoleConfig.class).runRole()).isEqualTo(RunRole.API);
    }

    @Test
    @DisplayName("PR01 不创建任何业务服务 bean")
    void noBusinessBeansYet() {
        // 骨架阶段的显式断言：避免「悄悄提前实现未来模块」
        assertThat(context.getBeanDefinitionNames())
                .noneMatch(name -> name.toLowerCase().contains("paymentservice"))
                .noneMatch(name -> name.toLowerCase().contains("refundservice"))
                .noneMatch(name -> name.toLowerCase().contains("ledgerposting"));
    }
}
