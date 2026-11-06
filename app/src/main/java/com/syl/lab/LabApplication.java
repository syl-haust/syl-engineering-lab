package com.syl.lab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 业务服务启动入口。
 *
 * <p>五个工程化课题的实现：</p>
 * <ol>
 *   <li><b>双 Token 认证</b>：Access Token 短命 + Refresh Token 轮换，
 *       解决 JWT 无法吊销、泄露后长时间有效的问题。</li>
 *   <li><b>可靠异步下单</b>：本地消息表 + RabbitMQ 延时队列，
 *       解决「写库与发消息不一致」「超时未支付不自动取消」。</li>
 *   <li><b>声明式幂等</b>：注解 + Redis + DB 唯一键三层，
 *       解决重复提交和超时重试带来的重复订单。</li>
 *   <li><b>WebSocket 实时推送</b>：MQ 消费后推送订单状态到浏览器，
 *       替代轮询，打通「消息 → 用户」的最后一公里。</li>
 * </ol>
 *
 * <p>限流与熔断在独立的 gateway 模块（端口 9000）。</p>
 */
@SpringBootApplication
@EnableScheduling
@EnableDiscoveryClient
public class LabApplication {

    public static void main(String[] args) {
        SpringApplication.run(LabApplication.class, args);
    }
}