package com.syl.lab.common;

import com.syl.lab.ws.OrderEventPusher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查与运行时状态。
 *
 * <p>{@code /api/health} 用于前端状态条检测服务是否在线；
 * {@code /api/ws/stats} 用于展示 WebSocket 在线用户数。</p>
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final OrderEventPusher pusher;

    public HealthController(OrderEventPusher pusher) {
        this.pusher = pusher;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("service", "lab-app");
        m.put("timestamp", System.currentTimeMillis());
        return m;
    }

    @GetMapping("/ws/stats")
    public Map<String, Object> wsStats() {
        return pusher.stats();
    }
}