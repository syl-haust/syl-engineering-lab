package com.syl.lab.gateway.circuitbreaker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 手写熔断器（状态机版）。
 *
 * <h3>为什么不用现成的 Resilience4j</h3>
 * <p>项目里已经引入了 {@code spring-cloud-starter-circuitbreaker-reactor-resilience4j}，
 * 生产环境我会直接用它的 {@code CircuitBreaker} 路由过滤器。这里手写一份的原因是
 * <b>面试要讲清楚三个状态怎么切换</b> —— 只用现成库的话，很多人只能说出
 * "失败多了就打开"，答不上来 <b>打开之后什么时候恢复</b>。</p>
 *
 * <h3>三个状态</h3>
 * <pre>
 *           失败率超阈值
 *  CLOSED ───────────────▶ OPEN
 *    ▲                      │
 *    │ 试探成功               │ 等待 waitDurationInOpenState
 *    │                      ▼
 *    └────────────────── HALF_OPEN
 *         试探失败 → 回到 OPEN
 * </pre>
 *
 * <ul>
 *   <li><b>CLOSED</b>：正常放行，统计滑动窗口内的失败率。</li>
 *   <li><b>OPEN</b>：直接短路返回降级响应，<b>一个请求都不放过下游</b>。
 *       这是保护下游的关键：熔断期间下游能喘口气恢复。</li>
 *   <li><b>HALF_OPEN</b>：放行<b>有限个</b>试探请求。
 *       全成功才回到 CLOSED，任何一个失败立刻回 OPEN。
 *       不是"过一段时间自动恢复"——那会让下游刚恢复就被打垮。</li>
 * </ul>
 */
@Component
public class SimpleCircuitBreakerFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SimpleCircuitBreakerFilter.class);

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    /** CLOSED 状态下的滑动窗口计数 */
    private final AtomicInteger totalInWindow = new AtomicInteger(0);
    private final AtomicInteger failuresInWindow = new AtomicInteger(0);
    /** OPEN 状态打开的绝对时间（毫秒），用于判断何时进入 HALF_OPEN */
    private volatile long openedAt = 0;
    /** HALF_OPEN 状态已放行的试探请求数 */
    private final AtomicInteger halfOpenPermits = new AtomicInteger(0);

    private final CircuitBreakerProperties props;

    public SimpleCircuitBreakerFilter(CircuitBreakerProperties props) {
        this.props = props;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!props.isEnabled()) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getURI().getPath();
        if (!props.matches(path)) {
            return chain.filter(exchange);
        }

        State current = currentState();
        if (current == State.OPEN) {
            // 熔断中：直接降级，不碰下游
            return fallback(exchange, "服务已熔断，请稍后重试");
        }
        if (current == State.HALF_OPEN) {
            // 半开：只放行有限个试探请求，其余仍降级
            if (halfOpenPermits.incrementAndGet() > props.getHalfOpenPermits()) {
                return fallback(exchange, "服务恢复探测中，请稍后重试");
            }
        }

        long start = System.currentTimeMillis();
        return chain.filter(exchange)
                .doOnError(err -> recordFailure(current, err))
                .onErrorResume(err -> fallback(exchange, "下游服务异常，已降级"))
                .doOnSuccess(v -> {
                    // 只有 5xx 才算失败；4xx 是客户端问题，不该熔断
                    HttpStatus status = exchange.getResponse().getStatusCode() == null
                            ? HttpStatus.OK
                            : HttpStatus.valueOf(exchange.getResponse().getStatusCode().value());
                    if (status.is5xxServerError()) {
                        recordFailure(current, new IllegalStateException("status=" + status));
                    } else {
                        recordSuccess(current);
                    }
                    long cost = System.currentTimeMillis() - start;
                    if (cost > props.getSlowCallMillis()) {
                        log.warn("慢调用 {} 耗时 {}ms（阈值 {}ms）", path, cost, props.getSlowCallMillis());
                    }
                });
    }

    /** 根据当前时间决定实际状态：OPEN 超时后自动转 HALF_OPEN */
    private State currentState() {
        State s = state.get();
        if (s == State.OPEN
                && System.currentTimeMillis() - openedAt >= props.getWaitDurationInOpenStateMillis()) {
            if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                halfOpenPermits.set(0);
                log.warn("熔断器进入 HALF_OPEN，开始试探下游");
            }
            return state.get();
        }
        return s;
    }

    private void recordFailure(State enteredState, Throwable err) {
        if (enteredState == State.HALF_OPEN) {
            // 半开状态下失败：说明下游还没好，立刻回到 OPEN
            open(err);
            return;
        }
        int total = totalInWindow.incrementAndGet();
        int failures = failuresInWindow.incrementAndGet();
        if (total >= props.getMinimumRequests()) {
            double rate = (double) failures / total;
            if (rate >= props.getFailureRateThreshold()) {
                open(err);
            } else {
                resetWindowIfNeeded();
            }
        }
    }

    private void recordSuccess(State enteredState) {
        if (enteredState == State.HALF_OPEN) {
            // 半开成功：试探请求都成功了，关闭熔断
            if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                resetWindow();
                log.info("熔断器恢复为 CLOSED，下游已恢复");
            }
            return;
        }
        int total = totalInWindow.incrementAndGet();
        if (total >= props.getMinimumRequests()) {
            resetWindowIfNeeded();
        }
    }

    private void open(Throwable err) {
        if (state.compareAndSet(State.CLOSED, State.OPEN)
                || state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
            openedAt = System.currentTimeMillis();
            log.error("熔断器打开 OPEN，原因: {}", err.getMessage());
        }
        resetWindow();
    }

    private void resetWindow() {
        totalInWindow.set(0);
        failuresInWindow.set(0);
    }

    /** 窗口满且失败率恢复正常后，把窗口清空重新统计 */
    private void resetWindowIfNeeded() {
        if (totalInWindow.get() >= props.getMinimumRequests()) {
            resetWindow();
        }
    }

    private Mono<Void> fallback(ServerWebExchange exchange, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().add("X-Circuit-State", state.get().name());
        String body = "{\"error\":\"SERVICE_DEGRADED\",\"message\":\"" + message + "\"}";
        return response.writeWith(Mono.just(
                response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public int getOrder() {
        // 在限流之后执行：先挡流量，再判熔断
        return -90;
    }

    /** 供调试接口读取当前状态 */
    public String stateName() {
        return currentState().name();
    }
}