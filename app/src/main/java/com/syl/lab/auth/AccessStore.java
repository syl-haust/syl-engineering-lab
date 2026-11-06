package com.syl.lab.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Access Token 白名单（可选的一层）。
 *
 * <p>两个用途：</p>
 * <ol>
 *   <li><b>主动踢人</b>：把 jti 从白名单删掉，access token 即便未过期也不认。</li>
 *   <li><b>登出</b>：同上。</li>
 * </ol>
 *
 * <p>如果对性能极其敏感，可以不校验 access 白名单（纯无状态），
 * 代价是踢人最多延迟 15 分钟生效。本项目默认开启，便于演示。</p>
 */
@Component
public class AccessStore {

    private static final String PREFIX = "lab:auth:access:";

    private final StringRedisTemplate redis;
    private final JwtService jwtService;

    public AccessStore(StringRedisTemplate redis, JwtService jwtService) {
        this.redis = redis;
        this.jwtService = jwtService;
    }

    public void save(String jti, Duration ttl) {
        redis.opsForValue().set(PREFIX + jti, "1", ttl);
    }

    public boolean isValid(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(PREFIX + jti));
    }

    public void revoke(String jti) {
        redis.delete(PREFIX + jti);
    }
}