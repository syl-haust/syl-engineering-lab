package com.syl.lab.idempotent;

import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Collections;

/**
 * 幂等切面。
 *
 * <h3>三层防护，各管一段</h3>
 * <ol>
 *   <li><b>第一层：请求头 Idempotency-Key</b> —— 由前端/网关生成，
 *       同一次用户操作的重试带同一个 key。这是"用户意图级"的唯一标识。</li>
 *   <li><b>第二层：Redis SETNX</b> —— 原子占位。
 *       用 Lua 区分「首次」和「处理中」，避免请求还在跑时，
 *       重试请求直接返回空结果给用户。</li>
 *   <li><b>第三层：数据库唯一索引</b> —— Redis 集群抖动/过期后仍能兜住。
 *       见 {@link IdempotentRecordMapper}。</li>
 * </ol>
 *
 * <h3>为什么用 Lua 而不是直接 SETNX</h3>
 * <p>单用 {@code setIfAbsent} 只能区分"第一次"和"非第一次"，
 * 无法区分「第一次还在处理中」和「第一次已处理完」。
 * 前者应该告诉调用方"请稍后重试"，后者应该直接回放结果。
 * 用 Lua 在 Redis 里存 3 种状态（PROCESSING / DONE），一次原子读改写就解决了。</p>
 */
@Aspect
@Component
public class IdempotentAspect {

    private static final String PREFIX = "lab:idem:";
    private static final String S_PROCESSING = "PROCESSING";
    private static final String S_DONE = "DONE";

    /** 返回 1 = 占位成功(PROCESSING)，2 = 已完成(DONE)，3 = 正在处理中 */
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('get', KEYS[1]) "
                    + "if v == false then "
                    + "  redis.call('set', KEYS[1], ARGV[1], 'EX', ARGV[2]) "
                    + "  return 1 "
                    + "end "
                    + "if v == ARGV[3] then return 2 end "
                    + "return 3",
            Long.class);

    private final StringRedisTemplate redis;
    private final IdempotentRecordMapper recordMapper;
    private final ResultCodec codec;

    public IdempotentAspect(StringRedisTemplate redis, IdempotentRecordMapper recordMapper, ResultCodec codec) {
        this.redis = redis;
        this.recordMapper = recordMapper;
        this.codec = codec;
    }

    @Around("@annotation(com.syl.lab.idempotent.Idempotent)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        Idempotent anno = method.getAnnotation(Idempotent.class);

        long userId = currentUserId();
        String bizKey = buildKey(anno.prefix(), userId, method.getName());

        Long acquired = redis.execute(ACQUIRE_SCRIPT,
                Collections.singletonList(PREFIX + bizKey),
                S_PROCESSING, String.valueOf(anno.ttlSeconds()), S_DONE);

        if (acquired != null && acquired == 2L) {
            // 已完成：直接回放上次结果
            String cached = recordMapper.findResult(bizKey);
            if (cached != null) {
                return codec.decode(cached, method.getReturnType());
            }
            // Redis 说有结果但 DB 没有，说明数据被清理了，降级为按处理中处理
        }
        if (acquired != null && acquired == 3L) {
            throw new DuplicateRequestException("请求正在处理中，请勿重复提交");
        }

        // 第三层：DB 唯一索引兜底。Redis 是快路径，DB 是最终保证。
        // 注意这里必须在执行业务之前插入，否则无法挡住"Redis 恰好过期"的窗口。
        if (recordMapper.tryInsert(bizKey, anno.prefix(), (int) anno.ttlSeconds()) == 0) {
            // DB 里已经有这条记录，说明是重复请求
            String previous = recordMapper.findResult(bizKey);
            if (previous != null) {
                return codec.decode(previous, method.getReturnType());
            }
            throw new DuplicateRequestException("请求正在处理中，请勿重复提交");
        }

        // 首次执行
        try {
            Object result = pjp.proceed();
            finish(bizKey, anno, result);
            return result;
        } catch (Throwable ex) {
            // 业务失败要释放占位，否则用户重试会被一直挡住
            release(bizKey);
            throw ex;
        }
    }

    private void finish(String bizKey, Idempotent anno, Object result) {
        String json = anno.replayResult() ? codec.encode(result) : null;
        recordMapper.markDone(bizKey, json);
        // 把状态从 PROCESSING 改成 DONE，TTL 不变
        redis.opsForValue().set(PREFIX + bizKey, S_DONE,
                java.time.Duration.ofSeconds(anno.ttlSeconds()));
    }

    private void release(String bizKey) {
        redis.delete(PREFIX + bizKey);
        recordMapper.delete(bizKey);
    }

    private String buildKey(String prefix, long userId, String methodName) {
        String requestKey = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            requestKey = req.getHeader("Idempotency-Key");
            if (requestKey == null || requestKey.isBlank()) {
                requestKey = req.getParameter("requestKey");
            }
        }
        // 没有显式 key 时退化成 用户+方法 维度，仍然能拦住连点
        String tail = (requestKey == null || requestKey.isBlank())
                ? "no-key"
                : requestKey;
        return prefix + ":" + userId + ":" + methodName + ":" + tail;
    }

    private long currentUserId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            Object v = attrs.getRequest().getAttribute(com.syl.lab.auth.AuthInterceptor.ATTR_USER_ID);
            if (v instanceof Long id) {
                return id;
            }
        }
        return 0L;
    }
}