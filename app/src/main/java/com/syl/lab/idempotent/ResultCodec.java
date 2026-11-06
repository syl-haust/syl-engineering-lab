package com.syl.lab.idempotent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 返回值序列化/反序列化。
 *
 * <p>幂等回放要求"第二次请求返回和第一次完全一样的结果"，
 * 所以要把首次返回值存下来，用 JSON 存比 Java 序列化更稳、更可读。</p>
 */
@Component
public class ResultCodec {

    private final ObjectMapper objectMapper;

    public ResultCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果序列化失败", e);
        }
    }

    public Object decode(String json, Class<?> targetType) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, targetType);
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果反序列化失败", e);
        }
    }
}