package com.syl.lab.idempotent;

/** 重复请求（同一幂等键正在处理中） */
public class DuplicateRequestException extends RuntimeException {

    public DuplicateRequestException(String message) {
        super(message);
    }
}