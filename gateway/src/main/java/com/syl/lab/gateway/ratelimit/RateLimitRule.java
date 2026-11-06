package com.syl.lab.gateway.ratelimit;

/**
 * 单条限流规则。
 *
 * <p>令牌桶的两个参数要分开理解：</p>
 * <ul>
 *   <li><b>capacity（容量）</b>：允许的突发上限。用户攒够了令牌能一次性打这么多。</li>
 *   <li><b>refillPerSecond（补充速率）</b>：长期平均速率。</li>
 * </ul>
 * <p>例如 {@code capacity=5, refill=2} 表示：稳态每秒 2 次，但允许瞬间打出 5 次。</p>
 */
public class RateLimitRule {

    public enum Dimension {
        /** 按用户限流（优先用 X-User-Id，取不到退回 IP） */
        USER,
        /** 按来源 IP 限流 */
        IP,
        /** 按接口维度限流（所有用户共享一个桶） */
        API
    }

    private String path = "/**";
    private Dimension dimension = Dimension.USER;
    private int capacity = 10;
    private double refillPerSecond = 5;

    /** Ant 风格匹配：支持 ** 和 * */
    boolean matches(String requestPath) {
        String p = this.path;
        if (p.endsWith("/**")) {
            String prefix = p.substring(0, p.length() - 3);
            return requestPath.equals(prefix) || requestPath.startsWith(prefix + "/");
        }
        if (p.contains("*")) {
            String regex = p.replace(".", "\\.").replace("**", ".*").replace("*", "[^/]*");
            return requestPath.matches(regex);
        }
        return requestPath.equals(p);
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Dimension getDimension() {
        return dimension;
    }

    public void setDimension(Dimension dimension) {
        this.dimension = dimension;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public double getRefillPerSecond() {
        return refillPerSecond;
    }

    public void setRefillPerSecond(double refillPerSecond) {
        this.refillPerSecond = refillPerSecond;
    }
}