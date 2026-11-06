package com.syl.lab.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syl.lab.order.DemoOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 本地消息表的补偿发送器。
 *
 * <h3>解决什么问题</h3>
 * <p>"写订单"和"发 MQ"是两件事，无法放进同一个本地事务。
 * 常见错误做法是先发 MQ 再写库（消息先到、订单还没落库，消费端查不到），
 * 或者先写库再发 MQ（发消息时进程挂了，消息永久丢失）。</p>
 *
 * <h3>本项目的做法：本地消息表（Transactional Outbox）</h3>
 * <ol>
 *   <li>下单事务里，订单和消息记录<b>一起写库</b>，status=0。</li>
 *   <li>事务提交后立刻发一次 MQ（低延迟路径）。</li>
 *   <li>本类定时扫描 status=0 的记录补发（兜底路径）。</li>
 *   <li>发送成功置 status=1，并按指数退避设置 next_retry_at，避免雪崩式重试。</li>
 * </ol>
 *
 * <p>代价是消息会重复（至少一次语义），所以消费端必须幂等 —— 这正是
 * {@link OrderConsumer} 里用 messageId 去重的原因。</p>
 */
@Component
public class LocalMessageRelay {

    private static final Logger log = LoggerFactory.getLogger(LocalMessageRelay.class);

    private final LocalMessageMapper messageMapper;
    private final ReliableSender sender;
    private final ObjectMapper objectMapper;
    private final DemoOrderMapper orderMapper;

    public LocalMessageRelay(LocalMessageMapper messageMapper, ReliableSender sender,
                             ObjectMapper objectMapper, DemoOrderMapper orderMapper) {
        this.messageMapper = messageMapper;
        this.sender = sender;
        this.objectMapper = objectMapper;
        this.orderMapper = orderMapper;
    }

    /** 每 5 秒扫一次待发送消息；生产环境应换成分布式调度（避免多实例重复扫） */
    @Scheduled(fixedDelay = 5000)
    public void relay() {
        List<Map<String, Object>> pending = messageMapper.findPending(100);
        for (Map<String, Object> row : pending) {
            long id = ((Number) row.get("id")).longValue();
            String messageId = (String) row.get("message_id");
            String exchange = (String) row.get("exchange");
            String routingKey = (String) row.get("routing_key");
            String payload = (String) row.get("payload");
            int retry = ((Number) row.get("retry_count")).intValue();

            try {
                OrderMessage message = objectMapper.readValue(payload, OrderMessage.class);
                boolean ack = sender.sendAndWait(exchange, routingKey, message, 3000);
                if (ack) {
                    messageMapper.markSent(id);
                    log.info("本地消息补发成功 messageId={}", messageId);
                } else {
                    scheduleRetry(id, retry);
                }
            } catch (Exception e) {
                log.warn("本地消息补发失败 id={} err={}", id, e.getMessage());
                scheduleRetry(id, retry);
            }
        }
    }

    /**
     * 指数退避重试：1s, 2s, 4s, 8s ... 上限 5 分钟。
     * 直接用固定间隔重试会把 broker 打爆，尤其当 broker 已经挂掉时。
     */
    private void scheduleRetry(long id, int retryCount) {
        long delaySeconds = Math.min(300, (long) Math.pow(2, Math.min(retryCount, 8)));
        Timestamp next = Timestamp.from(Instant.now().plusSeconds(delaySeconds));
        messageMapper.incrementRetry(id, next);
    }

    /** 清理已完成且超过 7 天的消息记录，避免表无限膨胀 */
    @Scheduled(cron = "0 0 3 * * ?")
    public void purgeOld() {
        int n = messageMapper.purgeFinished(7);
        log.info("清理本地消息表 {} 条", n);
    }
}