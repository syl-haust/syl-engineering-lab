package com.syl.lab.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

@Repository
public class JdbcDemoOrderMapper implements DemoOrderMapper {

    private final JdbcTemplate jdbc;

    public JdbcDemoOrderMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insert(String orderNo, long userId, long skuId, BigDecimal amount) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "insert into demo_order(order_no, user_id, sku_id, amount, status) values (?, ?, ?, ?, 0)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, orderNo);
            ps.setLong(2, userId);
            ps.setLong(3, skuId);
            ps.setBigDecimal(4, amount);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("订单主键生成失败");
        }
        return key.longValue();
    }

    /**
     * 取消订单的幂等写法：把状态判断放进 SQL 的 where 里。
     *
     * <p>不用 {@code where id=?} 直接 update，是因为重复消费时
     * 第二次会把已支付的订单误改成已取消。带上 {@code status = 0}
     * 后，第二次 update 影响行数为 0，天然幂等。</p>
     */
    @Override
    public int cancelIfUnpaid(long orderId) {
        return jdbc.update("update demo_order set status = 2 where id = ? and status = 0", orderId);
    }

    @Override
    public int markPaid(long orderId) {
        return jdbc.update("update demo_order set status = 1 where id = ? and status = 0", orderId);
    }

    @Override
    public Map<String, Object> findById(long orderId) {
        List<Map<String, Object>> list = jdbc.queryForList(
                "select * from demo_order where id = ?", orderId);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public List<Map<String, Object>> findByUser(long userId) {
        return jdbc.queryForList(
                "select * from demo_order where user_id = ? order by id desc limit 50", userId);
    }
}