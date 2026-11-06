package com.syl.lab.idempotent;

/** 幂等记录读写（第三层防护：DB 唯一索引兜底） */
public interface IdempotentRecordMapper {

    /** 首次占位：依赖 uk_biz_key 唯一索引，插入冲突说明已有记录 */
    int tryInsert(String bizKey, String bizType, int ttlSeconds);

    void markDone(String bizKey, String resultJson);

    String findResult(String bizKey);

    void delete(String bizKey);
}