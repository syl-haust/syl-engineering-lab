package com.syl.lab.idempotent;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 基于 JdbcTemplate 的幂等记录实现 */
@Repository
public class JdbcIdempotentRecordMapper implements IdempotentRecordMapper {

    private final JdbcTemplate jdbc;

    public JdbcIdempotentRecordMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int tryInsert(String bizKey, String bizType, int ttlSeconds) {
        try {
            jdbc.update(
                    "insert into idempotent_record(biz_key, biz_type, status, expire_at) values (?, ?, 0, ?)",
                    bizKey, bizType, Timestamp.from(Instant.now().plus(ttlSeconds, ChronoUnit.SECONDS)));
            return 1;
        } catch (DuplicateKeyException e) {
            return 0;
        }
    }

    @Override
    public void markDone(String bizKey, String resultJson) {
        jdbc.update("update idempotent_record set status = 1, result = ? where biz_key = ?",
                resultJson, bizKey);
    }

    @Override
    public String findResult(String bizKey) {
        var list = jdbc.queryForList(
                "select result from idempotent_record where biz_key = ? and status = 1",
                String.class, bizKey);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public void delete(String bizKey) {
        jdbc.update("delete from idempotent_record where biz_key = ?", bizKey);
    }
}