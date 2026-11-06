package com.syl.lab.mq;

/** 已消费消息记录（消费端幂等的落点） */
public interface ConsumedMessageMapper {

    /** 插入成功返回 1，主键/唯一键冲突返回 0，表示重复消息 */
    int tryConsume(String messageId);
}