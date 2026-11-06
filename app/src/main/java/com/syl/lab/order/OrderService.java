package com.syl.lab.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syl.lab.idempotent.Idempotent;
import com.syl.lab.mq.LocalMessageMapper;
import com.syl.lab.mq.MqConfig;
import com.syl.lab.mq.OrderMessage;
import com.syl.lab.mq.ReliableSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * 下单服务：把三个亮点串成一条完整链路。
 *
 * <h3>下单的完整时序</h3>
 * <pre>
 *  1. @Idempotent 拦截          -> 重复请求直接返回上次结果
 *  2. 事务开始
 *  3.   insert demo_order      -> 订单落库
 *  4.   insert local_message   -> 延时消息也落库（status=0）
 *  5. 事务提交
 *  6. 立即发 MQ（低延迟路径）；失败由 LocalMessageRelay 定时补发（兜底路径）
 *  7. 30 分钟后消息进过期队列 -> 自动取消未支付订单
 * </pre>
 *
 * <p>第 3、4 步在同一个事务里是关键：订单和"要发什么消息"要么都成功，
 * 要么都失败，不会出现"订单有了但消息永远发不出去"。</p>
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** 延时队列的分钟数，仅用于返回给前端展示；真实 TTL 在 MqConfig 里 */
    public static final int DELAY_MINUTES = 30;

    private final DemoOrderMapper orderMapper;
    private final LocalMessageMapper messageMapper;
    private final ReliableSender sender;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public OrderService(DemoOrderMapper orderMapper, LocalMessageMapper messageMapper,
                        ReliableSender sender, TransactionTemplate tx, ObjectMapper objectMapper) {
        this.orderMapper = orderMapper;
        this.messageMapper = messageMapper;
        this.sender = sender;
        this.tx = tx;
        this.objectMapper = objectMapper;
    }

    public record SubmitCommand(long userId, long skuId, BigDecimal amount) {
    }

    /**
     * 提交订单（幂等）。
     *
     * <p>{@code @Idempotent} 的 key 维度是 prefix + userId + method + requestKey，
     * 前端每次点击生成一个 requestKey，重试时复用同一个 —— 这样"用户重试"
     * 和"用户真的想再下一单"可以被区分开。</p>
     */
    @Idempotent(prefix = "order:submit", ttlSeconds = 300)
    public Map<String, Object> submit(SubmitCommand cmd) {
        SubmitContext ctx = new SubmitContext();

        long orderId = tx.execute(status -> {
            String orderNo = "SO" + System.currentTimeMillis()
                    + UUID.randomUUID().toString().substring(0, 6);
            long id = orderMapper.insert(orderNo, cmd.userId(), cmd.skuId(), cmd.amount());

            // 关键：同一个 messageId 既写进本地消息表，也用于投递 MQ。
            // 这样定时补发和事务后立即投递用的是同一条消息，
            // 消费端靠 messageId 去重，不会因为"发了两遍"而重复处理。
            String messageId = UUID.randomUUID().toString();
            OrderMessage msg = new OrderMessage(
                    messageId, id, orderNo, cmd.userId(), OrderMessage.EVENT_EXPIRE);
            try {
                messageMapper.insert(msg.messageId(), MqConfig.DELAY_EXCHANGE,
                        MqConfig.DELAY_KEY, objectMapper.writeValueAsString(msg));
            } catch (Exception e) {
                throw new IllegalStateException("序列化延时消息失败", e);
            }
            ctx.messageId = messageId;
            ctx.orderNo = orderNo;
            return id;
        });

        // 事务提交后再发消息；发失败也不影响下单，本地消息表会兜底补发
        OrderMessage msg = new OrderMessage(
                ctx.messageId, orderId, ctx.orderNo, cmd.userId(), OrderMessage.EVENT_EXPIRE);
        try {
            boolean ack = sender.sendAndWait(MqConfig.DELAY_EXCHANGE, MqConfig.DELAY_KEY, msg, 2000);
            if (!ack) {
                log.warn("首次发送延时消息未确认，等待补偿 orderId={}", orderId);
            }
        } catch (Exception e) {
            log.warn("首次发送延时消息异常，等待补偿 orderId={} err={}", orderId, e.getMessage());
        }

        Map<String, Object> order = new java.util.LinkedHashMap<>(orderMapper.findById(orderId));
        order.put("messageId", ctx.messageId);
        order.put("delayMinutes", DELAY_MINUTES);
        return order;
    }

    /** 事务内产生的、事务外还要用的值 */
    private static final class SubmitContext {
        String messageId;
        String orderNo;
    }

    public void pay(long orderId) {
        int n = orderMapper.markPaid(orderId);
        if (n == 0) {
            throw new IllegalStateException("订单状态不允许支付");
        }
    }

    public java.util.List<java.util.Map<String, Object>> findByUser(long userId) {
        return orderMapper.findByUser(userId);
    }
}