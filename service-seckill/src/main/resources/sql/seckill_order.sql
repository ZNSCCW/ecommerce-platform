-- 秒杀订单表（秒杀成功生成的订单）
-- 需添加到 docs/init.sql 的秒杀服务区域
CREATE DATABASE IF NOT EXISTS `db_seckill` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE db_seckill;

CREATE TABLE IF NOT EXISTS `t_seckill_order` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_no` varchar(32) NOT NULL COMMENT '秒杀订单号',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `activity_id` bigint NOT NULL COMMENT '秒杀活动ID',
  `sku_id` bigint NOT NULL COMMENT 'SKU ID',
  `seckill_price` decimal(10,2) NOT NULL COMMENT '秒杀价格',
  `quantity` int NOT NULL DEFAULT '1' COMMENT '数量',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待支付 1-已支付 2-已取消',
  `deleted` tinyint NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_activity_id` (`activity_id`),
  KEY `idx_sku_id` (`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀订单表';
