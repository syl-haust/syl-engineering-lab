package com.syl.lab.ws;

import com.syl.lab.auth.AuthService;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

/**
 * WebSocket 握手鉴权。
 *
 * <h3>为什么必须在握手时鉴权</h3>
 * <p>WebSocket 建立后是一条裸 TCP 长连接，后续帧不会经过 Spring MVC 的拦截器。
 * 如果在握手阶段不校验身份，任何人都能连上来，
 * 而且连接会一直存在直到主动断开 —— 比 HTTP 的越权危险得多。</p>
 *
 * <h3>token 怎么传</h3>
 * <p>标准 WebSocket API 不能自定义 Header，所以只能放 query parameter：
 * <code>ws://host/ws/orders?token=xxx</code>。
 * 这里复用亮点①的 <b>Access Token</b>，校验签名 + Redis 白名单，
 * 被踢下线的用户连 WS 也连不上 —— 两个亮点就此串起来了。</p>
 */
@Component
public class WsAuthInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "wsUserId";

    private final AuthService authService;

    public WsAuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = extractToken(request.getURI());
        if (token == null) {
            return false;
        }
        long userId = authService.authenticatedUserId(token);
        if (userId < 0) {
            return false;
        }
        attributes.put(ATTR_USER_ID, userId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }

    /** 支持 ?token=xxx 与 ?access_token=xxx 两种写法 */
    private String extractToken(URI uri) {
        String query = uri.getQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String k = pair.substring(0, idx);
            if ("token".equals(k) || "access_token".equals(k)) {
                return pair.substring(idx + 1);
            }
        }
        return null;
    }
}