package com.rag.studyhelper.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 通用限流注解
 * <p>
 * aop 使用在注册了 spring bean 的类方法上，由 {@link RateLimitAspect} 拦截并执行限流。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    /**
     * 限流 key
     */
    String value();

    /**
     * 令牌桶容量
     */
    long maxCapacity();

    /**
     * 补充令牌的时间，默认 1 分钟补充 count 个令牌
     * 默认秒
     */
    long supplementTimeOfSeconds() default 60L;

    /**
     * 每日最大调用次数，默认 0 表示不限制
     */
    int dailyMaximumCount() default 0;

    /**
     * 超时时间 单位，默认 24 小时
     */
    long timeOutOfHours() default 24L;
}
