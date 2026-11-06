package com.syl.lab.mq;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/** 本地消息表读写 */
@Repository
public interface LocalMessageMapper {

    /** 写入一条待发送消息（与业务在同一个事务里） */
    void insert(String messageId, String exchange, String routingKey, String payload);

    /** 找到期且未发送的消息 */
    List<Map<String, Object>> findPending(int limit);

    void markSent(long id);

    /** 递增重试次数并设置下次重试时间（指数退避） */
    void incrementRetry(long id, java.sql.Timestamp nextRetryAt);

    /** 清理已发送且创建时间早于 N 天的记录 */
    int purgeFinished(int days);
}