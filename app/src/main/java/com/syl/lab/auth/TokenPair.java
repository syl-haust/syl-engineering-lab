package com.syl.lab.auth;

import java.time.Instant;

/**
 * 登录/刷新返回的令牌对。
 *
 * <p>refreshTokenExpiresAt 让前端能在过期前主动刷新，
 * 而不是等到 401 再被动处理（被动处理会造成一次请求失败）。</p>
 */
public record TokenPair(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds,
        Instant refreshTokenExpiresAt) {
}