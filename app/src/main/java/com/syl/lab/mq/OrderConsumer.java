package com.syl.lab.mq;

import com.syl.lab.order.DemoOrderMapper;
import com.syl.lab.ws.OrderEventPusher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 订单消息消费者。
 *
 * <h3>RabbitMQ 的三条投递语义，选哪条</h3>
 * <ul>
 *   <li>最多一次（at-most-once）：消费前先 ack，宕机就丢消息。</li>
 *   <li>至少一次（at-least-once）：处理完再 ack，宕机会重复投递 —— <b>本项目采用</b>。</li>
 *   <li>恰好一次：RabbitMQ 不提供端到端的恰好一次。</li>
 * </ul>
 *
 * <p>既然只能是"至少一次"，重复消费就必然发生，
 * 所以消费端幂等不是可选项，而是必须项。</p>
 *
 * <h3>幂等靠什么</h3>
 * <p>消息自带 {@code messageId}，消费前先尝试在消费记录表插入
 * （唯一索引），插入成功才真正处理；冲突说明处理过，直接 ack 丢弃。
 * 之所以不用"先查再插"，是因为查和插之间仍有并发窗口。</p>
 */
@Component
public class OrderConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderConsumer.class);

    private final DemoOrderMapper orderMapper;
    private final ConsumedMessageMapper consumedMapper;
    private final TransactionTemplate tx;
    private final OrderEventPusher pusher;

    public OrderConsumer(DemoOrderMapper orderMapper,
                         ConsumedMessageMapper consumedMapper,
                         TransactionTemplate tx,
                         OrderEventPusher pusher) {
        this.orderMapper = orderMapper;
        this.consumedMapper = consumedMapper;
        this.tx = tx;
        this.pusher = pusher;
    }

    /**
     * 监听"过期"队列：30 分钟未支付的订单在这里被取消。
     *
     * <p>注意：这里执行的是"取消"而不是"删除"，且要放在事务里，
     * 保证状态更新和消费记录同时成功或同时失败。</p>
     *
     * <p><b>亮点⑤的关键点：先提交事务，再推送 WebSocket。</b>
     * 如果推送写在事务里，事务回滚了用户却收到了"订单已取消"——
     * 这是很多人踩过的坑。所以用 affected 标记，事务结束后再推。</p>
     */
    @RabbitListener(queues = MqConfig.EXPIRE_QUEUE)
    public void onExpire(OrderMessage message) {
        boolean[] cancelled = {false};

        tx.executeWithoutResult(status -> {
            if (consumedMapper.tryConsume(message.messageId()) == 0) {
                log.info("重复消息，跳过 messageId={}", message.messageId());
                return;
            }
            int affected = orderMapper.cancelIfUnpaid(message.orderId());
            log.info("订单超时自动取消 orderId={} affected={}", message.orderId(), affected);
            cancelled[0] = affected > 0;
        });

        // 事务已提交，此时推送才安全
        if (cancelled[0]) {
            pusher.pushOrderStatus(message.userId(), message.orderId(), 2,
                    "订单超时未支付，已自动取消");
        }
    }

    /** 监听支付成功队列（示例，可扩展为发短信/加积分等下游动作） */
    @RabbitListener(queues = MqConfig.ORDER_PAID_QUEUE)
    public void onPaid(OrderMessage message) {
        tx.executeWithoutResult(status -> {
            if (consumedMapper.tryConsume(message.messageId()) == 0) {
                return;
            }
            log.info("订单支付成功后处理 orderId={}", message.orderId());
        });
        pusher.pushOrderStatus(message.userId(), message.orderId(), 1, "支付成功");
    }

    /** 死信队列：重试耗尽的消息落在这里，需要人工排查 */
    @RabbitListener(queues = MqConfig.DEAD_QUEUE)
    public void onDead(OrderMessage message) {
        log.error("消息进入死信队列，需人工处理: messageId={} orderId={}",
                message.messageId(), message.orderId());
    }
}