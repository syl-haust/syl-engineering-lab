package com.syl.lab.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * 令牌桶限流全局过滤器。
 *
 * <h3>为什么不用现成的 RequestRateLimiter</h3>
 * <p>Spring Cloud Gateway 自带的 {@code RequestRateLimiter} 用的是
 * <b>令牌桶的近似实现</b>（固定窗口 + 平滑），且行为受配置细节影响很大。
 * 这里手写一份，好处是：</p>
 * <ul>
 *   <li>算法完全可控，面试能一行行讲；</li>
 *   <li>可以按「用户 / IP / 接口」三种维度组合出 key；</li>
 *   <li>能返回带 {@code X-RateLimit-*} 响应头的标准限流响应。</li>
 * </ul>
 *
 * <h3>为什么用 Lua</h3>
 * <p>令牌桶的判定包含「算时间差 → 补充令牌 → 判断是否够扣 → 扣减」四步，
 * 如果分成多条 Redis 命令，并发下会出现超发（典型的 check-then-act 竞态）。
 * 放进一个 Lua 脚本，Redis 单线程执行，天然原子。</p>
 *
 * <h3>为什么用令牌桶而不是固定窗口</h3>
 * <p>固定窗口在窗口边界会被 <b>2 倍突发</b> 击穿：
 * 第 59 秒打满 100 次、第 61 秒又打满 100 次，相当于 2 秒内 200 次。
 * 令牌桶按恒定速率补充令牌，容量限制突发，没有边界问题。</p>
 */
@Component
public class TokenBucketRateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimitFilter.class);

    /** Lua 脚本：令牌桶算法，返回 1 表示放行，0 表示拒绝 */
    private static final RedisScript<Long> TOKEN_BUCKET_SCRIPT =
            RedisScript.of(new ClassPathResource("lua/token_bucket.lua"), Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final RateLimitProperties props;

    public TokenBucketRateLimitFilter(ReactiveStringRedisTemplate redis, RateLimitProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!props.isEnabled()) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getURI().getPath();
        RateLimitRule rule = props.match(path);
        if (rule == null) {
            // 该路径不在限流范围内（比如静态页面）
            return chain.filter(exchange);
        }

        String bucketKey = buildKey(exchange, rule);

        return redis.execute(
                        TOKEN_BUCKET_SCRIPT,
                        List.of(bucketKey),
                        List.of(
                                String.valueOf(rule.getCapacity()),
                                String.valueOf(rule.getRefillPerSecond()),
                                String.valueOf(Instant.now().toEpochMilli())
                        )
                )
                .next()
                .defaultIfEmpty(1L)
                .flatMap(allowed -> {
                    if (allowed == 1L) {
                        return chain.filter(exchange);
                    }
                    return reject(exchange, rule, bucketKey);
                })
                .onErrorResume(e -> {
                    // Redis 挂了不应该把整个网关打挂：放行（fail-open），并记日志告警。
                    // 反过来 fail-closed 更安全但可用性差，按业务选择。
                    log.error("限流器执行异常，降级为放行: {}", e.getMessage());
                    return chain.filter(exchange);
                });
    }

    /**
     * 构造限流 key，支持三种维度：
     * <ul>
     *   <li>{@code USER}：按用户 id（从 JWT 或请求头取），最精确</li>
     *   <li>{@code IP}：按来源 IP，防止未登录刷接口</li>
     *   <li>{@code API}：按接口路径，保护热点接口不被单点打爆</li>
     * </ul>
     * 组合起来是 {@code 限流维度:路径}，避免不同接口共用一个桶。
     */
    private String buildKey(ServerWebExchange exchange, RateLimitRule rule) {
        String path = rule.getPath();
        String dimension;
        switch (rule.getDimension()) {
            case USER -> {
                String user = exchange.getRequest().getHeaders().getFirst("X-User-Id");
                if (user == null || user.isBlank()) {
                    user = clientIp(exchange);
                }
                dimension = "u:" + user;
            }
            case IP -> dimension = "ip:" + clientIp(exchange);
            default -> dimension = "api";
        }
        return "lab:rl:" + dimension + ":" + path;
    }

    private String clientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null ? "unknown" : remote.getAddress().getHostAddress();
    }

    /** 被限流：返回 429 + 标准响应头 + 友好 JSON，而不是把异常抛给前端 */
    private Mono<Void> reject(ServerWebExchange exchange, RateLimitRule rule, String bucketKey) {
        log.warn("触发限流 path={} key={} rule={}tps/{}burst",
                rule.getPath(), bucketKey, rule.getRefillPerSecond(), rule.getCapacity());

        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().add("X-RateLimit-Limit", String.valueOf(rule.getCapacity()));
        response.getHeaders().add("X-RateLimit-Policy",
                rule.getRefillPerSecond() + "r/s, burst " + rule.getCapacity());
        response.getHeaders().add("Retry-After", "1");

        String body = "{\"error\":\"RATE_LIMITED\",\"path\":\"" + rule.getPath()
                + "\",\"message\":\"请求过于频繁，请稍后重试\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    @Override
    public int getOrder() {
        // 在路由转发前执行，且早于熔断器
        return -100;
    }
}