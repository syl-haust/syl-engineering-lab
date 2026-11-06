package com.syl.lab.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与解析。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>token 里带 {@code jti}（唯一 id），Redis 白名单用它做索引，
 *       这样同一个用户的多个端可以各自管理，踢人时按 jti 精确失效。</li>
 *   <li>token 里带 {@code typ}（access / refresh），
 *       防止把 refresh token 当 access token 用（这是常见的越权漏洞）。</li>
 *   <li>access token 只存 15 分钟，refresh token 存 7 天，
 *       角色/权限信息不在 token 里硬编码，改权限后下次刷新即可生效。</li>
 * </ul>
 */
@Component
public class JwtService {

    public static final String CLAIM_TYPE = "typ";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JwtService(
            @Value("${lab.auth.secret}") String secret,
            @Value("${lab.auth.access-ttl-minutes:15}") long accessTtlMinutes,
            @Value("${lab.auth.refresh-ttl-days:7}") long refreshTtlDays) {
        // HS256 要求密钥至少 256 bit，这里用调用方配置的字符串直接构造
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = Duration.ofMinutes(accessTtlMinutes);
        this.refreshTtl = Duration.ofDays(refreshTtlDays);
    }

    public Duration getAccessTtl() {
        return accessTtl;
    }

    public Duration getRefreshTtl() {
        return refreshTtl;
    }

    /**
     * 签发 token。
     *
     * @param jti 唯一标识，Redis 白名单的 key；refresh 二次签发时保持一致
     */
    public String issue(long userId, String jti, String type, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .id(jti)
                .claim(CLAIM_TYPE, type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** 解析并校验签名+过期时间；失败抛 JwtException，由调用方转成 401 */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }

    public static String newJti() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}