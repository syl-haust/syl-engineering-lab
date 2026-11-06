package com.syl.lab.auth;

import org.springframework.stereotype.Service;

/**
 * 演示用用户校验。
 *
 * <p>真实项目这里会查数据库 + BCrypt 校验；为了 demo 能零依赖启动，
 * 这里用一个固定的演示账号，密码明文比对。生产代码请务必换成
 * BCrypt + 查库，不要照抄这里。</p>
 */
@Service
public class UserService {

    private static final String DEMO_USER = "demo";
    private static final String DEMO_PASSWORD = "123456";

    public DemoUser authenticate(String username, String rawPassword) {
        if (!DEMO_USER.equals(username) || !DEMO_PASSWORD.equals(rawPassword)) {
            throw new BadCredentialsException("用户名或密码错误");
        }
        return new DemoUser(1L, DEMO_USER, "演示用户");
    }
}