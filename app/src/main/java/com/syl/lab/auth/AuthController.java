package com.syl.lab.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口。
 *
 * <p>错误码约定（前端按这个分支处理）：</p>
 * <ul>
 *   <li>401 用户名/密码错误，或 access token 失效需要去刷新</li>
 *   <li>409 refresh token 已被使用（并发刷新），前端应复用已有的新 token，不要跳登录</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;

    public AuthController(AuthService authService, JwtService jwtService) {
        this.authService = authService;
        this.jwtService = jwtService;
    }

    public record LoginRequest(String username, String password) {
    }

    public record RefreshRequest(String refreshToken) {
    }

    @PostMapping("/login")
    public TokenPair login(@RequestBody LoginRequest req) {
        return authService.login(req.username(), req.password());
    }

    @PostMapping("/refresh")
    public TokenPair refresh(@RequestBody RefreshRequest req) {
        return authService.refresh(req.refreshToken());
    }

    /** 登出当前会话：从 Authorization 头取出 access token 的 jti 一起作废 */
    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String jti = extractJti(authorization);
        if (jti != null) {
            authService.logout(jti);
        }
        return Map.of("logout", true);
    }

    private String extractJti(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        try {
            return jwtService.parse(authorization.substring(7)).getId();
        } catch (Exception e) {
            return null;
        }
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> onBadCredentials(BadCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "BAD_CREDENTIALS", "message", e.getMessage()));
    }

    @ExceptionHandler(RefreshRejectedException.class)
    public ResponseEntity<Map<String, Object>> onRefreshRejected(RefreshRejectedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "REFRESH_REJECTED", "message", e.getMessage()));
    }
}