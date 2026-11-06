package com.syl.lab.auth;

/** 刷新被拒绝（无效 token / 并发重复刷新） */
public class RefreshRejectedException extends RuntimeException {

    public RefreshRejectedException(String message) {
        super(message);
    }
}