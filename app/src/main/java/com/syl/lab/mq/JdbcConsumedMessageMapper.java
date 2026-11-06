package com.syl.lab.mq;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcConsumedMessageMapper implements ConsumedMessageMapper {

    private final JdbcTemplate jdbc;

    public JdbcConsumedMessageMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int tryConsume(String messageId) {
        try {
            jdbc.update("insert into consumed_message(message_id) values (?)", messageId);
            return 1;
        } catch (DuplicateKeyException e) {
            return 0;
        }
    }
}