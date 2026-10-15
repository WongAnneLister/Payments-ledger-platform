package com.paymentsledger.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.identity.AuthenticationFilter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Only the API role serves business routes, so only it rate limits. */
@Configuration(proxyBeanMethods=false)
@Profile("api")
@ConditionalOnProperty(name="platform.rate-limit.enabled",havingValue="true")
public class RateLimitConfiguration {
    @Bean RateLimiter rateLimiter(StringRedisTemplate redis){return new RedisTokenBucketLimiter(redis);}

    @Bean RateLimitFilter rateLimitFilter(RateLimiter limiter,Environment env,ObjectMapper mapper,Clock clock,MeterRegistry meters) {
        return new RateLimitFilter(limiter,policy(env,"read"),policy(env,"write"),AuthenticationFilter::authenticatedTenant,
                mapper,clock,env.getRequiredProperty("platform.rate-limit.bypass-after-failure",Duration.class),meters);
    }

    private static RateLimitPolicy policy(Environment env,String bucket) {
        return new RateLimitPolicy(env.getRequiredProperty("platform.rate-limit."+bucket+".capacity",Long.class),
                env.getRequiredProperty("platform.rate-limit."+bucket+".refill-per-second",Double.class));
    }
}
