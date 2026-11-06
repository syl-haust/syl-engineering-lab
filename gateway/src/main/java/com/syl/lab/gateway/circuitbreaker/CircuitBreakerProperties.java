package com.syl.lab.gateway.circuitbreaker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 熔断器配置，同样支持 Nacos 热更新。
 *
 * <p>参数含义（和 Resilience4j 的命名保持一致，方便迁移）：</p>
 * <ul>
 *   <li>failureRateThreshold：滑动窗口内失败率超过多少就打开</li>
 *   <li>minimumRequests：至少统计多少次才判断，避免「1 次失败就熔断」的误判</li>
 *   <li>waitDurationInOpenState：打开后等多久进入半开</li>
 *   <li>halfOpenPermits：半开状态放行几个试探请求</li>
 *   <li>slowCallMillis：超过多少毫秒算慢调用（仅打日志，不参与判定）</li>
 * </ul>
 */
@Component
@RefreshScope
@ConfigurationProperties(prefix = "lab.circuitbreaker")
public class CircuitBreakerProperties {

    private boolean enabled = true;
    private List<String> paths = new ArrayList<>(List.of("/api/**"));
    private int minimumRequests = 5;
    private double failureRateThreshold = 0.5;
    private long waitDurationInOpenStateMillis = 10000;
    private int halfOpenPermits = 2;
    private long slowCallMillis = 1000;

    public boolean matches(String path) {
        return paths.stream().anyMatch(p -> {
            if (p.endsWith("/**")) {
                String prefix = p.substring(0, p.length() - 3);
                return path.equals(prefix) || path.startsWith(prefix + "/");
            }
            return path.equals(p);
        });
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getPaths() {
        return paths;
    }

    public void setPaths(List<String> paths) {
        this.paths = paths;
    }

    public int getMinimumRequests() {
        return minimumRequests;
    }

    public void setMinimumRequests(int minimumRequests) {
        this.minimumRequests = minimumRequests;
    }

    public double getFailureRateThreshold() {
        return failureRateThreshold;
    }

    public void setFailureRateThreshold(double failureRateThreshold) {
        this.failureRateThreshold = failureRateThreshold;
    }

    public long getWaitDurationInOpenStateMillis() {
        return waitDurationInOpenStateMillis;
    }

    public void setWaitDurationInOpenStateMillis(long waitDurationInOpenStateMillis) {
        this.waitDurationInOpenStateMillis = waitDurationInOpenStateMillis;
    }

    public int getHalfOpenPermits() {
        return halfOpenPermits;
    }

    public void setHalfOpenPermits(int halfOpenPermits) {
        this.halfOpenPermits = halfOpenPermits;
    }

    public long getSlowCallMillis() {
        return slowCallMillis;
    }

    public void setSlowCallMillis(long slowCallMillis) {
        this.slowCallMillis = slowCallMillis;
    }
}