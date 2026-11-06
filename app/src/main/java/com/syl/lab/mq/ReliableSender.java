package com.syl.lab.mq;

import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 可靠消息发送器。
 *
 * <h3>为什么不能直接 rabbitTemplate.convertAndSend 就完事</h3>
 * <p>默认情况下，{@code convertAndSend} 把消息交给 channel 就返回了。
 * 如果 broker 拒收（交换机不存在、队列满、内存告警），
 * 消息会**静默丢失**，业务方毫不知情。</p>
 *
 * <p>两端都要配：</p>
 * <ul>
 *   <li><b>publisher-confirm</b>：broker 收到消息后回调 ack/nack，
 *       这里是"到没到交换机"。</li>
 *   <li><b>publisher-return</b>：消息到了交换机但没路由到任何队列时回调，
 *       这里是"有没有人收"。两者缺一不可。</li>
 * </ul>
 *
 * <p>本类基于 Spring AMQP 自带的 {@link CorrelationData} 等待 confirm：
 * 发送时把 CorrelationData 一起传进去，broker 的 ack/nack 会 settle 到
 * {@code correlationData.getFuture()} 上，调用方 get 一下即可拿到结果，
 * 不需要自己维护 correlationId → Future 的映射表。</p>
 */
@Component
public class ReliableSender {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ReliableSender.class);

    private final RabbitTemplate rabbitTemplate;

    public ReliableSender(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitTemplate.setReturnsCallback(returned -> {
            // 消息到达交换机但没有队列接收：用 messageId 找不到 correlationData 时只能记日志
            // 生产环境应把 returns 也接入告警
            System.err.println("[ReliableSender] message returned: " + returned.getReplyText()
                    + " routingKey=" + returned.getRoutingKey());
        });
        this.rabbitTemplate.setMandatory(true);
    }

    /** 发送并等待 broker confirm（同步，适合需要立即知道结果的场景） */
    public boolean sendAndWait(String exchange, String routingKey, OrderMessage message, long timeoutMs) {
        try {
            CorrelationData cd = new CorrelationData(UUID.randomUUID().toString());
            rabbitTemplate.convertAndSend(exchange, routingKey, message, cd);
            CorrelationData.Confirm confirm = cd.getFuture().get(timeoutMs, TimeUnit.MILLISECONDS);
            return confirm != null && confirm.isAck();
        } catch (Exception e) {
            log.warn("发送消息未获得 confirm: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 只发送不等待，由本地消息表在后续确认（异步、非阻塞）。
     *
     * <p>correlation id 用 messageId，便于在 confirm 回调里反查是哪条本地消息。</p>
     */
    public void sendAsync(String exchange, String routingKey, OrderMessage message) {
        CorrelationData cd = new CorrelationData(message.messageId());
        rabbitTemplate.convertAndSend(exchange, routingKey, message, cd);
    }
}