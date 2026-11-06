package com.syl.lab.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器：解析 Bearer token，写入请求属性供业务层取用。
 *
 * <p>只拦截 /api/**，放行 /api/auth/login 和 /api/auth/refresh。</p>
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_USER_ID = "lab.userId";

    private final AuthService authService;

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = request.getRequestURI();
        if (path.startsWith("/api/auth/login") || path.startsWith("/api/auth/refresh")) {
            return true;
        }
        // 健康检查与静态页面不需要鉴权
        if (path.startsWith("/api/health")) {
            return true;
        }
        if (!path.startsWith("/api/")) {
            return true;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeUnauthorized(response, "缺少 Authorization 头");
            return false;
        }

        long userId = authService.authenticatedUserId(header.substring(7));
        if (userId < 0) {
            writeUnauthorized(response, "access token 无效或已过期");
            return false;
        }
        request.setAttribute(ATTR_USER_ID, userId);
        return true;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"UNAUTHORIZED\",\"message\":\"" + message + "\"}");
    }
}