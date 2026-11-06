package com.syl.lab.mq;

/**
 * 订单事件消息体。
 *
 * <p>关键字段 {@code messageId}：生产端生成、消费端用它做幂等。
 * 之所以不用 {@code orderId} 做幂等键，是因为同一个订单可能产生
 * 多条语义不同的消息（下单、支付、取消），只用订单号会把它们混在一起。</p>
 */
public record OrderMessage(
        String messageId,
        Long orderId,
        String orderNo,
        Long userId,
        String eventType) {

    public static final String EVENT_CREATED = "ORDER_CREATED";
    public static final String EVENT_EXPIRE = "ORDER_EXPIRE";
    public static final String EVENT_PAID = "ORDER_PAID";
}