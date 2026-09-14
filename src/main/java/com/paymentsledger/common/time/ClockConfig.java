package com.paymentsledger.common.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟注入。**禁止**在业务代码中直接调用 {@code Instant.now()} /
 * {@code LocalDate.now()}（ADR-0002）。
 *
 * <p>理由：租约到期、L1 退避节奏、幂等键保留窗口、业务日 → UTC 区间转换
 * 全部依赖可控时钟才能写出确定性可重放的测试（AC-V1-08、09、30）。
 *
 * <p>一律 UTC 瞬时；业务时区只在「业务日 → UTC 区间」转换处显式引入。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
