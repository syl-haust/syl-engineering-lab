package com.syl.lab.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;

/**
 * Refresh Token 会话存储。
 *
 * <p>为什么 JWT 还要存 Redis？因为纯无状态 JWT 有两个解决不了的问题：</p>
 * <ol>
 *   <li><b>无法主动吊销</b>：token 签发出去就一直有效到过期。</li>
 *   <li><b>刷新轮换要防重放</b>：refresh token 换新后，旧的必须立刻失效，
 *       否则被截获的旧 token 可以在有效期内无限续期。</li>
 * </ol>
 *
 * <p>这里用「白名单」而不是「黑名单」：白名单只需要存活的 token，
 * 定期自动过期；黑名单要存所有被吊销的 token，只增不减。
 * 代价是多一次 Redis 查询，这里用 access token 只查一次换取。
 * </p>
 *
 * <p><b>并发刷新防重放</b>是这里最值得讲的点，见 {@link #rotate}。</p>
 */
@Component
public class RefreshStore {

    private static final String PREFIX = "lab:auth:refresh:";

    private final StringRedisTemplate redis;
    private final JwtService jwtService;

    public RefreshStore(StringRedisTemplate redis, JwtService jwtService) {
        this.redis = redis;
        this.jwtService = jwtService;
    }

    private String key(String jti) {
        return PREFIX + jti;
    }

    /** 保存 refresh 会话（jti -> userId:refreshToken），TTL 与 token 一致 */
    public void save(String jti, long userId) {
        redis.opsForValue().set(key(jti), String.valueOf(userId), jwtService.getRefreshTtl());
    }

    public boolean exists(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(key(jti)));
    }

    /**
     * <b>并发刷新防重放</b>的核心实现。
     *
     * <p>场景：用户打开多个标签页，前端可能在同一时刻发起 2~3 个刷新请求，
     * 拿的是同一个 refresh token。如果每个请求都「删除旧 token + 签发新 token」，
     * 只有最后一个成功，其余的会把用户踢下线 —— 这是双 Token 方案最容易被踩的坑。</p>
     *
     * <p>解法是用 Lua 把「判断存在 + 删除」做成一个原子操作：</p>
     * <ul>
     *   <li>脚本返回 1 的请求拿到刷新资格，签发新 token；</li>
     *   <li>返回 0 的请求说明别人刚刷过，直接返回 409，
     *       前端约定收到 409 就用本地已有的新 access token，而不是跳登录页。</li>
     * </ul>
     *
     * <p>这里不用 Redisson：Lua 脚本在 Redis 里单线程执行，本身就是原子的，
     * 引入分布式锁反而多一次 RTT 和一个故障点。</p>
     *
     * @return true 表示本请求获得刷新资格
     */
    public boolean rotate(String oldJti) {
        Long result = redis.execute(ROTATE_SCRIPT, Collections.singletonList(key(oldJti)));
        return Long.valueOf(1L).equals(result);
    }

    /**
     * Lua：存在才删并返回 1，不存在返回 0。
     * "判断 + 删除"必须在同一个脚本里，分成两个命令就有并发窗口。
     */
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> ROTATE_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('exists', KEYS[1]) == 1 then "
                            + "  redis.call('del', KEYS[1]) "
                            + "  return 1 "
                            + "end "
                            + "return 0",
                    Long.class);

    /** 主动踢人：删掉这个会话的 refresh token，access token 最多再活 15 分钟 */
    public void revoke(String jti) {
        redis.delete(key(jti));
    }

    /** 登录时给会话设置 TTL；这里单独抽出来方便测试替换 */
    public void save(String jti, long userId, Duration ttl) {
        redis.opsForValue().set(key(jti), String.valueOf(userId), ttl);
    }
}