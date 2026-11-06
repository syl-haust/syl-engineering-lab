package com.syl.lab.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置。
 *
 * <p>注意：<b>不要给这个端点加拦截器路径限制</b>。口是 /ws/orders，
 * 它不在 /api 下，所以 AuthInterceptor（HTTP 的）不会拦截它。</p>
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final OrderWebSocketHandler handler;
    private final WsAuthInterceptor authInterceptor;

    public WebSocketConfig(OrderWebSocketHandler handler, WsAuthInterceptor authInterceptor) {
        this.handler = handler;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/orders")
                .addInterceptors(authInterceptor)
                // 演示环境允许跨域；生产环境要收紧到具体域名
                .setAllowedOriginPatterns("*");
    }
}