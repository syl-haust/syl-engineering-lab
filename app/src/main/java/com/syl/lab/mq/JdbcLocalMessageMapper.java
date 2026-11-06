package com.syl.lab.mq;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** 本地消息表 JDBC 实现 */
@Repository
public class JdbcLocalMessageMapper implements LocalMessageMapper {

    private final JdbcTemplate jdbc;

    public JdbcLocalMessageMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(String messageId, String exchange, String routingKey, String payload) {
        jdbc.update(
                "insert into local_message(message_id, exchange, routing_key, payload, status) "
                        + "values (?, ?, ?, ?, 0)",
                messageId, exchange, routingKey, payload);
    }

    @Override
    public List<Map<String, Object>> findPending(int limit) {
        return jdbc.queryForList(
                "select id, message_id, exchange, routing_key, payload, retry_count "
                        + "from local_message "
                        + "where status = 0 and (next_retry_at is null or next_retry_at <= now()) "
                        + "order by id limit ?", limit);
    }

    @Override
    public void markSent(long id) {
        jdbc.update("update local_message set status = 1 where id = ?", id);
    }

    @Override
    public void incrementRetry(long id, Timestamp nextRetryAt) {
        jdbc.update("update local_message set retry_count = retry_count + 1, next_retry_at = ? where id = ?",
                nextRetryAt, id);
    }

    @Override
    public int purgeFinished(int days) {
        return jdbc.update(
                "delete from local_message where status = 1 and create_time < date_sub(now(), interval ? day)",
                days);
    }
}