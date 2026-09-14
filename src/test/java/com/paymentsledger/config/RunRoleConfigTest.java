package com.paymentsledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RunRoleConfigTest {

    @Test
    @DisplayName("恰好一个角色 profile 激活时解析成功")
    void resolvesSingleRole() {
        assertThat(RunRoleConfig.resolve(new String[] {"api"})).isEqualTo(RunRole.API);
        assertThat(RunRoleConfig.resolve(new String[] {"worker"})).isEqualTo(RunRole.WORKER);
        assertThat(RunRoleConfig.resolve(new String[] {"worker", "local"})).isEqualTo(RunRole.WORKER);
    }

    @Test
    @DisplayName("未指定角色时快速失败，不猜测")
    void rejectsMissingRole() {
        assertThatThrownBy(() -> RunRoleConfig.resolve(new String[] {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Exactly one run role");

        assertThatThrownBy(() -> RunRoleConfig.resolve(new String[] {"local"}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("同时激活 api 与 worker 被拒绝：崩溃域与故障注入点必须可区分")
    void rejectsBothRoles() {
        assertThatThrownBy(() -> RunRoleConfig.resolve(new String[] {"api", "worker"}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Exactly one run role");
    }
}
