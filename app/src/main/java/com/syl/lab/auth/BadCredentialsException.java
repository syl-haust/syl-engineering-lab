package com.syl.lab.auth;

/** 账号或密码错误 */
public class BadCredentialsException extends RuntimeException {

    public BadCredentialsException(String message) {
        super(message);
    }
}