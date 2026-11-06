package com.syl.lab.gateway;

import com.syl.lab.gateway.circuitbreaker.SimpleCircuitBreakerFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关自检接口（不走限流与熔断，便于演示时查看状态）。
 */
@RestController
@RequestMapping("/gateway")
public class GatewayDebugController {

    private final SimpleCircuitBreakerFilter breaker;

    public GatewayDebugController(SimpleCircuitBreakerFilter breaker) {
        this.breaker = breaker;
    }

    @GetMapping("/state")
    public Mono<Map<String, Object>> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("circuitState", breaker.stateName());
        m.put("status", "UP");
        return Mono.just(m);
    }
}