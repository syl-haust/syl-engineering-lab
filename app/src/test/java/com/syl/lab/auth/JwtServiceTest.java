package com.syl.lab.auth;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * JWT 签发与解析的单元测试（不依赖 Redis / MySQL）。
 */
class JwtServiceTest {

    private final JwtService jwt = new JwtService(
            "unit-test-secret-key-at-least-32-bytes-long!!",
            15, 7);

    @Test
    void issueAndParse_roundTrip() {
        String jti = JwtService.newJti();
        String token = jwt.issue(42L, jti, JwtService.TYPE_ACCESS, jwt.getAccessTtl());

        Claims claims = jwt.parse(token);
        assertEquals("42", claims.getSubject());
        assertEquals(jti, claims.getId());
        assertEquals(JwtService.TYPE_ACCESS, claims.get(JwtService.CLAIM_TYPE, String.class));
    }

    @Test
    void differentJti_eachLogin() {
        // 每次登录生成不同 jti，这样多端登录互不影响，踢人也能按会话精确粒度
        assertNotEquals(JwtService.newJti(), JwtService.newJti());
    }

    @Test
    void expiredToken_throws() {
        String token = jwt.issue(1L, JwtService.newJti(), JwtService.TYPE_ACCESS, Duration.ofSeconds(-1));
        assertThrows(Exception.class, () -> jwt.parse(token));
    }

    @Test
    void tamperedToken_throws() {
        String token = jwt.issue(1L, JwtService.newJti(), JwtService.TYPE_ACCESS, jwt.getAccessTtl());
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThrows(Exception.class, () -> jwt.parse(tampered));
    }
}