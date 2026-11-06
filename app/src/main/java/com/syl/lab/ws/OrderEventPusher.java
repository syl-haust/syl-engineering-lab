package com.syl.lab.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 订单事件推送服务。
 *
 * <p>把「领域事件」翻译成「WebSocket 消息」，业务代码只依赖本类，
 * 不直接碰 WebSocket 会话 —— 这样以后换成 SSE / 推送服务只改这一处。</p>
 */
@Service
public class OrderEventPusher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPusher.class);

    private final OrderWebSocketHandler handler;

    public OrderEventPusher(OrderWebSocketHandler handler) {
        this.handler = handler;
    }

    /**
     * 推送订单状态变化。
     *
     * <p>消息体设计成 {@code {type, orderId, status, message, timestamp}}，
     * 前端用 type 分支处理，避免以后加事件类型要改协议。</p>
     *
     * @return 实际推送到的连接数（0 表示用户当前不在线，前端下次进页面靠接口补数据即可）
     */
    public int pushOrderStatus(long userId, long orderId, int status, String message) {
        String json = "{\"type\":\"ORDER_STATUS\","
                + "\"orderId\":" + orderId + ","
                + "\"status\":" + status + ","
                + "\"message\":\"" + escape(message) + "\","
                + "\"timestamp\":" + System.currentTimeMillis() + "}";
        int sent = handler.pushToUser(userId, json);
        log.info("订单状态推送 userId={} orderId={} status={} 送达连接数={}", userId, orderId, status, sent);
        return sent;
    }

    public Map<String, Object> stats() {
        return Map.of("onlineUsers", handler.onlineUsers());
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}