package com.syl.lab.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 双 Token 认证服务。
 *
 * <h3>整体流程</h3>
 * <pre>
 * 登录   POST /api/auth/login    -> {accessToken(15m), refreshToken(7d)}
 * 访问   GET  /api/xxx           -> Header: Authorization: Bearer {accessToken}
 * 刷新   POST /api/auth/refresh  -> 用 refreshToken 换一对新的
 * 登出   POST /api/auth/logout   -> 删掉本条会话的 refresh，access 自然过期
 * </pre>
 *
 * <h3>为什么不用单 token / 不用 session</h3>
 * <ul>
 *   <li>纯 session：服务端有状态，水平扩展要共享存储，移动端不友好。</li>
 *   <li>纯 JWT 单 token：要么有效期设很短（用户天天掉线），
 *       要么设很长（泄露后长期有效且无法吊销）。双 token 是这两者的折中。</li>
 * </ul>
 */
@Service
public class AuthService {

    private final JwtService jwtService;
    private final RefreshStore refreshStore;
    private final AccessStore accessStore;
    private final UserService userService;

    public AuthService(JwtService jwtService, RefreshStore refreshStore,
                       AccessStore accessStore, UserService userService) {
        this.jwtService = jwtService;
        this.refreshStore = refreshStore;
        this.accessStore = accessStore;
        this.userService = userService;
    }

    /** 登录：校验账号密码，签发一对 token */
    public TokenPair login(String username, String rawPassword) {
        DemoUser user = userService.authenticate(username, rawPassword);

        String jti = JwtService.newJti();
        String access = jwtService.issue(user.id(), jti, JwtService.TYPE_ACCESS, jwtService.getAccessTtl());
        String refresh = jwtService.issue(user.id(), jti, JwtService.TYPE_REFRESH, jwtService.getRefreshTtl());

        refreshStore.save(jti, user.id());
        accessStore.save(jti, jwtService.getAccessTtl());
        // access 和 refresh 共用同一个 jti：踢人时一次删两个 key 即可
        return new TokenPair(access, refresh, "Bearer",
                jwtService.getAccessTtl().toSeconds(),
                Instant.now().plus(jwtService.getRefreshTtl()));
    }

    /**
     * 刷新：轮换 refresh token。
     *
     * <p>注意这是「轮换」不是「续期」：旧 refresh 立刻失效，换发全新的 jti。
     * 这样即使旧 refresh 被截获，攻击者拿到的也是已失效的 token，
     * 而且用户正常使用时会发现被顶号 —— 等于一个弱告警。</p>
     *
     * @throws RefreshRejectedException 旧 token 已被使用过（并发刷新或重放）
     */
    public TokenPair refresh(String refreshToken) {
        Claims claims;
        try {
            claims = jwtService.parse(refreshToken);
        } catch (JwtException e) {
            throw new RefreshRejectedException("refresh token 无效或已过期");
        }
        if (!JwtService.TYPE_REFRESH.equals(claims.get(JwtService.CLAIM_TYPE, String.class))) {
            throw new RefreshRejectedException("token 类型错误");
        }

        long userId = Long.parseLong(claims.getSubject());
        String oldJti = claims.getId();

        // 原子删除：只有一个并发请求能成功，其余抛 RefreshRejectedException
        if (!refreshStore.rotate(oldJti)) {
            throw new RefreshRejectedException("refresh token 已被使用，请勿重复刷新");
        }
        // 旧 access 一起作废，避免 refresh 换了新凭证、旧 access 还能用
        accessStore.revoke(oldJti);

        String newJti = JwtService.newJti();
        String access = jwtService.issue(userId, newJti, JwtService.TYPE_ACCESS, jwtService.getAccessTtl());
        String refresh = jwtService.issue(userId, newJti, JwtService.TYPE_REFRESH, jwtService.getRefreshTtl());
        refreshStore.save(newJti, userId);
        accessStore.save(newJti, jwtService.getAccessTtl());

        return new TokenPair(access, refresh, "Bearer",
                jwtService.getAccessTtl().toSeconds(),
                Instant.now().plus(jwtService.getRefreshTtl()));
    }

    /** 登出 / 踢人：删除该 jti 对应的 refresh 与 access */
    public void logout(String jti) {
        refreshStore.revoke(jti);
        accessStore.revoke(jti);
    }

    /**
     * 校验 access token，返回用户 id。
     *
     * @return -1 表示无效（签名错、过期、类型不对、已被踢）
     */
    public long authenticatedUserId(String accessToken) {
        try {
            Claims claims = jwtService.parse(accessToken);
            if (!JwtService.TYPE_ACCESS.equals(claims.get(JwtService.CLAIM_TYPE, String.class))) {
                return -1;
            }
            String jti = claims.getId();
            if (!accessStore.isValid(jti)) {
                return -1;
            }
            return Long.parseLong(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return -1;
        }
    }
}