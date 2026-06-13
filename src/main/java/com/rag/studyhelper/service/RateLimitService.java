package com.rag.studyhelper.service;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redisson 分布式令牌桶的限流服务
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;

    public RateLimitService(RedissonClient redissonClient, StringRedisTemplate redisTemplate) {
        this.redissonClient = redissonClient;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 分布式令牌桶限流
     *
     * @param key            限流 key
     * @param maxCapacity  桶容量上限
     * @param supplementTimeOfSeconds 补充周期（秒）
     * @param timeOutOfHours 过期时间（小时）
     * @return true 允许通过，false 触发限流
     */
    public boolean tryAcquire(String key, long maxCapacity, long supplementTimeOfSeconds, long timeOutOfHours) {
        // 将配置值编码进 key，变更配置即自动切换限流器，旧 key 随 TTL 过期
        RRateLimiter limiter = redissonClient.getRateLimiter("rl:" + key + ":" + maxCapacity + ":" + supplementTimeOfSeconds + ":" + timeOutOfHours);
        if (!limiter.isExists()) {
            // RateType.OVERALL：所有实例共享同一桶（如三个实例，maxCapacity=10, supplementTimeOfSeconds=600 → 三个实例加起来每10分钟最多访问10次）
            // RateType.PER_CLIENT：每个实例独立桶（三个实例各自每10分钟最多10次）
            // maxCapacity：桶容量（最大令牌数）
            // supplementTimeOfSeconds：补充周期（每隔该秒数补充令牌，补充量为 maxCapacity）
            // 例子：maxCapacity=10, supplementTimeOfSeconds=60 → 每60秒补充10个令牌
            limiter.trySetRate(RateType.OVERALL, maxCapacity, Duration.ofSeconds(supplementTimeOfSeconds), Duration.ofHours(timeOutOfHours));
        }
        return limiter.tryAcquire();
    }

    /**
     * 全局每日调用计数限流。
     * <p>
     * 使用 INCR + EXPIRE 实现，每日零点自动重置。
     *
     * @param keyPrefix key 前缀
     * @param maxCalls  每日最大调用次数
     * @return true 允许通过，false 超限
     */
    public boolean tryDaily(String keyPrefix, int maxCalls) {
        String key = "rl:daily:" + keyPrefix + ":" + LocalDate.now();
        Long count = redisTemplate.opsForValue().increment(key);
        // 首次创建时设置过期时间，28 小时后自动删除
        if (count != null && count == 1L) {
            redisTemplate.expire(key, 28, TimeUnit.HOURS);
        }
        if (count != null && count > maxCalls) {
            log.warn("每日调用超限: key={}, count={}, max={}", keyPrefix, count, maxCalls);
            return false;
        }
        return true;
    }
}
