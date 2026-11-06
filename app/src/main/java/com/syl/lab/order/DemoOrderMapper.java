package com.syl.lab.order;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 演示订单持久化 */
public interface DemoOrderMapper {

    long insert(String orderNo, long userId, long skuId, BigDecimal amount);

    /** 只有待支付状态才取消，返回影响行数；返回 0 说明已支付或已取消，天然幂等 */
    int cancelIfUnpaid(long orderId);

    int markPaid(long orderId);

    Map<String, Object> findById(long orderId);

    List<Map<String, Object>> findByUser(long userId);
}