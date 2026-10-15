package com.paymentsledger.config;

import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 启动时校验运行角色，**恰好一个**角色 profile 必须激活。
 *
 * <p>快速失败优于运行期歧义：一个既是 API 又是 Worker 的进程会让崩溃域、
 * 扩缩容边界和故障注入点（F-06）全部失去意义。
 */
@Configuration
public class RunRoleConfig {

    private static final Logger log = LoggerFactory.getLogger(RunRoleConfig.class);

    private final RunRole runRole;

    public RunRoleConfig(Environment environment) {
        this.runRole = resolve(environment.getActiveProfiles());
        log.info("run_role_resolved role={} ", runRole);
    }

    public RunRole runRole() {
        return runRole;
    }

    static RunRole resolve(String[] activeProfiles) {
        List<String> active = Arrays.asList(activeProfiles);
        List<RunRole> matched = Arrays.stream(RunRole.values())
                .filter(role -> active.contains(role.profile()))
                .toList();

        if (matched.size() != 1) {
            throw new IllegalStateException("Exactly one run role profile must be active, got " + matched
                    + " from active profiles " + active
                    + ". Start with --spring.profiles.active=api or =worker.");
        }
        return matched.get(0);
    }
}
