package com.paymentsledger.ratelimit;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;

/** Real Redis 7: the Lua script is the unit under test, so it cannot be mocked. */
@Testcontainers
class RedisTokenBucketLimiterIT {
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    RedisTokenBucketLimiter limiter;
    String key;

    @BeforeEach void open() {
        factory=new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(),REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();factory.start();
        redis=new StringRedisTemplate(factory);
        limiter=new RedisTokenBucketLimiter(redis);
        key="rl:{test:"+UUID.randomUUID()+"}:write";
    }
    @AfterEach void close(){factory.destroy();}

    @Test void burstUpToCapacityThenRejectsWithRetryAfter() {
        var policy=new RateLimitPolicy(3,1);
        for(long remaining=2;remaining>=0;remaining--) {
            var decision=limiter.acquire(key,policy);
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.remaining()).isEqualTo(remaining);
        }
        var rejected=limiter.acquire(key,policy);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(1));
    }

    @Test void rejectedCallsDoNotSpendTokensAndRefillRestoresCapacity() throws Exception {
        var policy=new RateLimitPolicy(2,10);
        assertThat(limiter.acquire(key,policy).allowed()).isTrue();
        assertThat(limiter.acquire(key,policy).allowed()).isTrue();
        assertThat(limiter.acquire(key,policy).allowed()).isFalse();
        assertThat(limiter.acquire(key,policy).allowed()).isFalse();
        Thread.sleep(300); // 10 tokens/s: about three tokens, capped at capacity 2
        assertThat(limiter.acquire(key,policy).allowed()).isTrue();
        assertThat(limiter.acquire(key,policy).allowed()).isTrue();
        assertThat(limiter.acquire(key,policy).allowed()).isFalse();
    }

    @Test void concurrentCallersNeverSpendMoreThanCapacity() throws Exception {
        var policy=new RateLimitPolicy(10,0.001);
        var allowed=new AtomicInteger();
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(16)) {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<100;i++)futures.add(pool.submit(()->{start.await();if(limiter.acquire(key,policy).allowed())allowed.incrementAndGet();return null;}));
            start.countDown();
            for(var future:futures)future.get(30,TimeUnit.SECONDS);
        }
        assertThat(allowed.get()).isEqualTo(10);
    }

    @Test void bucketsAreIndependentPerKey() {
        var policy=new RateLimitPolicy(1,0.001);
        assertThat(limiter.acquire(key,policy).allowed()).isTrue();
        assertThat(limiter.acquire(key,policy).allowed()).isFalse();
        assertThat(limiter.acquire(key.replace(":write",":read"),policy).allowed()).isTrue();
    }

    @Test void idleBucketsExpireOnceFullyRefilled() {
        limiter.acquire(key,new RateLimitPolicy(5,10));
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isBetween(1L,1_500L);
        assertThat(redis.opsForHash().get(key,"ts").toString()).matches("[0-9]{16}");
    }
}
