package com.syl.lab.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 网关启动类。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>把 /api/** 转发到 app 服务（通过 Nacos 服务发现拿到实例）</li>
 *   <li>令牌桶限流（Redis Lua），阈值从 Nacos 配置中心热更新</li>
 *   <li>熔断降级（CLOSED / OPEN / HALF_OPEN 三态）</li>
 * </ol>
 */
@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}