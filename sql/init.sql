-- ============================================================
-- syl-engineering-lab 初始化脚本
-- 只建五个课题所需的表，不包含任何业务系统的表。
-- ============================================================

CREATE DATABASE IF NOT EXISTS syl_lab DEFAULT CHARSET utf8mb4;

USE syl_lab;

-- ------------------------------------------------------------
-- 亮点 ②：本地消息表
-- 作用：保证「业务写库」与「发 MQ」的一致性。
-- 下单事务里先写这张表(status=0)，再由定时任务扫描发送，
-- 发送成功置 status=1；MQ 确认没回来就重试，不会丢单。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `local_message`;
CREATE TABLE `local_message` (
  `id`          bigint       NOT NULL AUTO_INCREMENT,
  `message_id`  varchar(64)  NOT NULL COMMENT '业务消息ID，消费端用它做幂等',
  `exchange`    varchar(128) NOT NULL,
  `routing_key` varchar(128) NOT NULL,
  `payload`     text         NOT NULL COMMENT '消息体 JSON',
  `status`      tinyint      NOT NULL DEFAULT 0 COMMENT '0-待发送 1-已发送 2-已消费确认',
  `retry_count` int          NOT NULL DEFAULT 0,
  `next_retry_at` datetime   NULL COMMENT '下次重试时间（退避）',
  `create_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_message_id` (`message_id`),
  KEY `idx_status_retry` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';

-- ------------------------------------------------------------
-- 亮点 ③：幂等记录表
-- 作用：兜住 Redis 失效/过期后仍然重复提交的请求。
-- 唯一索引是最后一道防线：Redis 只是快速路径，DB 唯一键才是最终保证。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `idempotent_record`;
CREATE TABLE `idempotent_record` (
  `id`          bigint       NOT NULL AUTO_INCREMENT,
  `biz_key`     varchar(128) NOT NULL COMMENT '业务幂等键，如 order:submit:用户ID:请求号',
  `biz_type`    varchar(64)  NOT NULL,
  `result`      text         NULL COMMENT '首次执行的返回值，重复请求直接回放',
  `status`      tinyint      NOT NULL DEFAULT 0 COMMENT '0-处理中 1-已完成',
  `expire_at`   datetime     NOT NULL,
  `create_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz_key` (`biz_key`),
  KEY `idx_expire` (`expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口幂等记录';

-- ------------------------------------------------------------
-- 亮点 ②：消费端幂等表
-- 作用：MQ 只能保证「至少一次」，重复投递必然发生，
-- 用唯一索引兜住：插入成功才处理，冲突说明处理过，直接 ack。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `consumed_message`;
CREATE TABLE `consumed_message` (
  `message_id`  varchar(64) NOT NULL,
  `consume_time` timestamp  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='已消费消息';

-- ------------------------------------------------------------
-- 演示用订单表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `demo_order`;
CREATE TABLE `demo_order` (
  `id`          bigint       NOT NULL AUTO_INCREMENT,
  `order_no`    varchar(64)  NOT NULL,
  `user_id`     bigint       NOT NULL,
  `sku_id`      bigint       NOT NULL,
  `amount`      decimal(10,2) NOT NULL,
  `status`      tinyint      NOT NULL DEFAULT 0 COMMENT '0-待支付 1-已支付 2-已取消',
  `create_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演示订单';

-- ------------------------------------------------------------
-- 演示用账号（密码全部是 123456，BCrypt 前缀）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `demo_user`;
CREATE TABLE `demo_user` (
  `id`         bigint      NOT NULL AUTO_INCREMENT,
  `username`   varchar(64) NOT NULL,
  `password`   varchar(128) NOT NULL,
  `nickname`   varchar(64) NOT NULL,
  `status`     tinyint     NOT NULL DEFAULT 1,
  `create_time` timestamp  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演示用户';

INSERT INTO `demo_user` (`username`, `password`, `nickname`) VALUES
('demo', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '演示用户');