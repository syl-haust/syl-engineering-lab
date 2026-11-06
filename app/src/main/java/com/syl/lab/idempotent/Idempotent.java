package com.syl.lab.idempotent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式幂等注解。
 *
 * <p>用法：在需要防重复提交的方法上加 {@code @Idempotent}，
 * 由 {@link IdempotentAspect} 统一处理，业务代码零侵入。</p>
 *
 * <pre>
 * &#64;PostMapping("/submit")
 * &#64;Idempotent(prefix = "order:submit", ttlSeconds = 300)
 * public Result submit(&#64;RequestBody OrderRequest req) { ... }
 * </pre>
 *
 * <p><b>为什么用注解而不是每个接口手写</b>：幂等逻辑一旦散落在业务代码里，
 * 后面加接口就会漏；注解能把"必须幂等"变成一道可review、可强制检查的约束。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /** 幂等键前缀，通常是 业务域:动作 */
    String prefix();

    /** 记录保留时长（秒） */
    long ttlSeconds() default 300;

    /** 是否把首次返回值缓存下来，重复请求直接回放（默认开启） */
    boolean replayResult() default true;
}