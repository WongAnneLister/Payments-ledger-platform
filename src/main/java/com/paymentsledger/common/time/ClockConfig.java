package com.paymentsledger.common.time;

import java.time.Clock;
import java.time.Duration;
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
 *
 * <p>精度截断到微秒：Linux 上 {@code Clock.systemUTC()} 是纳秒精度，而 PostgreSQL
 * timestamptz 只存微秒并在写入时四舍五入。若不截断，写入的 {@code available_at}
 * 读回后可能比 Java 侧的 {@code now} 晚几百纳秒，到期任务会被误判为未到期。
 */
@Configuration
public class ClockConfig {

    static final Duration DATABASE_PRECISION = Duration.ofNanos(1_000);

    @Bean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), DATABASE_PRECISION);
    }
}
