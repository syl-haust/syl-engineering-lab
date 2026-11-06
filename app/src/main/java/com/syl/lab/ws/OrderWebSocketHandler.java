package com.syl.lab.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 处理器：按用户维护会话，支持一用户多端。
 *
 * <h3>为什么不用轮询</h3>
 * <p>订单状态变化（支付成功 / 超时取消）如果不推送，前端只能每几秒查一次：
 * 大量无效请求、状态变更延迟、服务器压力大。
 * WebSocket 长连接把「服务器主动通知」变成可能。</p>
 *
 * <h3>会话管理的关键点</h3>
 * <ul>
 *   <li><b>Map&lt;userId, Set&lt;session&gt;&gt;</b>：
 *       一个用户可能同时开手机和电脑，所以是 Set 不是单个 session。</li>
 *   <li><b>用 ConcurrentHashMap + 线程安全 Set</b>：
 *       WebSocket 回调在 IO 线程执行，推送又来自 MQ 消费线程，必然并发访问。</li>
 *   <li><b>session 关闭必须清理</b>：否则内存泄漏，且推送到已断开的连接会抛异常。</li>
 * </ul>
 */
@Component
public class OrderWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderWebSocketHandler.class);

    /** userId -> 该用户所有存活连接 */
    private final Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get(WsAuthInterceptor.ATTR_USER_ID);
        if (userId == null) {
            closeQuietly(session, CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        sessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("WebSocket 连接建立 userId={} sessionId={} 当前连接数={}",
                userId, session.getId(), sessions.get(userId).size());
        sendQuietly(session, "{\"type\":\"CONNECTED\",\"userId\":" + userId + "}");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 客户端心跳：回 PONG 即可，用于保活和检测半死连接
        if ("PING".equalsIgnoreCase(message.getPayload().trim())) {
            sendQuietly(session, "{\"type\":\"PONG\"}");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long) session.getAttributes().get(WsAuthInterceptor.ATTR_USER_ID);
        if (userId == null) {
            return;
        }
        Set<WebSocketSession> set = sessions.get(userId);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) {
                sessions.remove(userId);
            }
        }
        log.info("WebSocket 连接关闭 userId={} status={}", userId, status);
    }

    /**
     * 向指定用户的所有连接推送消息。
     *
     * <p>返回实际送达的连接数，便于调用方判断用户是否在线。</p>
     */
    public int pushToUser(long userId, String json) {
        Set<WebSocketSession> set = sessions.get(userId);
        if (set == null || set.isEmpty()) {
            return 0;
        }
        int sent = 0;
        for (WebSocketSession s : set) {
            if (s.isOpen() && sendQuietly(s, json)) {
                sent++;
            } else {
                set.remove(s);
            }
        }
        return sent;
    }

    public int onlineUsers() {
        return sessions.size();
    }

    private boolean sendQuietly(WebSocketSession session, String payload) {
        try {
            synchronized (session) {
                // WebSocketSession 不是线程安全的，并发 send 会抛 IllegalStateException
                session.sendMessage(new TextMessage(payload));
            }
            return true;
        } catch (IOException | IllegalStateException e) {
            log.warn("推送失败 session={} err={}", session.getId(), e.getMessage());
            return false;
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException ignored) {
            // 关闭失败无需处理
        }
    }
}