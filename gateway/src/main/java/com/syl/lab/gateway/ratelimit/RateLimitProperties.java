package com.syl.lab.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 限流配置。
 *
 * <p><b>@RefreshScope 是这里的关键。</b>加了这个注解后，Nacos 里的配置变更
 * 会触发这个 Bean 重新绑定属性，<b>不需要重启网关</b>。
 * 面试常问「怎么做到线上改阈值不重启」，答案就在这里。</p>
 *
 * <p>配置示例（application.yml 或 Nacos 的 <code>gateway-ratelimit.yml</code>）：</p>
 * <pre>
 * lab:
 *   ratelimit:
 *     enabled: true
 *     rules:
 *       - path: /api/order/**
 *         dimension: USER
 *         capacity: 5
 *         refill-per-second: 2
 * </pre>
 */
@Component
@RefreshScope
@ConfigurationProperties(prefix = "lab.ratelimit")
public class RateLimitProperties {

    private boolean enabled = true;

    private List<RateLimitRule> rules = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<RateLimitRule> getRules() {
        return rules;
    }

    public void setRules(List<RateLimitRule> rules) {
        this.rules = rules;
    }

    /**
     * path 支持 Ant 风格通配（/api/order/**），匹配时取<b>最长前缀</b>。
     *
     * <p>为什么取最长而不是第一个：规则里可能同时有 {@code /api/**} 的兜底规则
     * 和 {@code /api/order/**} 的专项规则，必须让更具体的生效。</p>
     */
    public RateLimitRule match(String path) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        String normalized = path.replaceAll("/+", "/");
        return rules.stream()
                .filter(r -> r.matches(normalized))
                .max(Comparator.comparingInt(r -> r.getPath().length()))
                .orElse(null);
    }
}