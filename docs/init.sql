-- ==========================================================
-- 微服务架构电商平台 - 数据库初始化脚本
-- 使用方式：用 Navicat 连接 MySQL (root/123456) 后直接导入此文件
-- 会自动创建 db_user / db_product / db_order / db_seckill / db_payment
-- 五个数据库及所有表和数据
-- ==========================================================

-- ==========================================================
-- 1️⃣ 用户服务 (db_user)
-- ==========================================================

CREATE DATABASE IF NOT EXISTS `db_user` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE db_user;

-- 用户表
CREATE TABLE IF NOT EXISTS `t_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username` varchar(32) NOT NULL COMMENT '用户名',
  `password` varchar(128) NOT NULL COMMENT '密码（BCrypt加密）',
  `phone` varchar(16) DEFAULT NULL COMMENT '手机号',
  `email` varchar(64) DEFAULT NULL COMMENT '邮箱',
  `avatar` varchar(256) DEFAULT NULL COMMENT '头像URL',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0-禁用 1-正常',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0-未删 1-已删',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_phone` (`phone`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 角色表
CREATE TABLE IF NOT EXISTS `t_role` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `name` varchar(32) NOT NULL COMMENT '角色名称',
  `code` varchar(32) NOT NULL COMMENT '角色编码（如 ADMIN / USER）',
  `description` varchar(128) DEFAULT NULL COMMENT '角色描述',
  `deleted` tinyint NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- 权限表
CREATE TABLE IF NOT EXISTS `t_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '权限ID',
  `name` varchar(64) NOT NULL COMMENT '权限名称',
  `permission` varchar(128) NOT NULL COMMENT '权限标识（如 user:create）',
  `type` tinyint NOT NULL DEFAULT '1' COMMENT '类型：1-菜单 2-按钮 3-API',
  `parent_id` bigint DEFAULT NULL COMMENT '父权限ID',
  `path` varchar(128) DEFAULT NULL COMMENT '路由路径',
  `sort` int DEFAULT '0' COMMENT '排序',
  `deleted` tinyint NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_permission` (`permission`),
  KEY `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限表';

-- 用户角色关联表
CREATE TABLE IF NOT EXISTS `t_user_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`,`role_id`),
  KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联表';

-- 角色权限关联表
CREATE TABLE IF NOT EXISTS `t_role_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `permission_id` bigint NOT NULL COMMENT '权限ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_permission` (`role_id`,`permission_id`),
  KEY `idx_permission_id` (`permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限关联表';

-- 初始数据：默认管理员账号 admin / 密码 admin123
INSERT INTO `t_user` (`id`, `username`, `password`, `phone`, `status`) VALUES
(1, 'admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7FAt3LqC', '13800000000', 1);

INSERT INTO `t_role` (`id`, `name`, `code`, `description`) VALUES
(1, '管理员', 'ADMIN', '系统管理员'),
(2, '普通用户', 'USER', '普通注册用户');

INSERT INTO `t_user_role` (`user_id`, `role_id`) VALUES (1, 1);


-- ==========================================================
-- 2️⃣ 商品服务 (db_product)
-- ==========================================================

CREATE DATABASE IF NOT EXISTS `db_product` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE db_product;

-- 商品分类表
CREATE TABLE IF NOT EXISTS `t_category` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '分类ID',
  `name` varchar(32) NOT NULL COMMENT '分类名称',
  `parent_id` bigint DEFAULT NULL COMMENT '父分类ID（null=顶级分类）',
  `level` tinyint NOT NULL DEFAULT '1' COMMENT '层级：1/2/3',
  `icon` varchar(256) DEFAULT NULL COMMENT '分类图标',
  `sort` int DEFAULT '0' COMMENT '排序',
  `deleted` tinyint NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类表';

-- SPU表（标准产品单元）
CREATE TABLE IF NOT EXISTS `t_spu` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'SPU ID',
  `name` varchar(128) NOT NULL COMMENT '商品名称',
  `brand` varchar(64) DEFAULT NULL COMMENT '品牌',
  `description` text COMMENT '商品描述',
  `main_image` varchar(256) DEFAULT NULL COMMENT '主图',
  `category_id` bigint NOT NULL COMMENT '分类ID',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-下架 1-上架',
  `deleted` tinyint NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_category_id` (`category_id`),
  KEY `idx_status` (`status`),
  FULLTEXT KEY `ft_name` (`name`) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SPU表';

-- SKU表（库存量单位）
CREATE TABLE IF NOT EXISTS `t_sku` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'SKU ID',
  `spu_id` bigint NOT NULL COMMENT '所属SPU',
  `name` varchar(128) NOT NULL COMMENT 'SKU名称（如 iPhone 15 Pro Max 256GB 黑色）',
  `specs` json DEFAULT NULL COMMENT '规格属性（如 {"颜色":"黑色","内存":"256GB"}）',
  `price` decimal(10,2) NOT NULL COMMENT '价格（分）',
  `stock` int NOT NULL DEFAULT '0' COMMENT '库存',
  `image` varchar(256) DEFAULT NULL COMMENT 'SKU图片',
  `deleted` tinyint NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_spu_id` (`spu_id`),
  KEY `idx_price` (`price`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU表';

-- 初始数据：示例商品
INSERT INTO `t_category` (`id`, `name`, `parent_id`, `level`) VALUES
(1, '手机数码', NULL, 1),
(2, '电脑办公', NULL, 1),
(3, '智能手表', 1, 2);

INSERT INTO `t_spu` (`id`, `name`, `brand`, `category_id`, `status`) VALUES
(1, 'iPhone 15 Pro Max', 'Apple', 1, 1);

INSERT INTO `t_sku` (`id`, `spu_id`, `name`, `specs`, `price`, `stock`) VALUES
(1, 1, 'iPhone 15 Pro Max 256GB 原色钛金属',
 '{"颜色":"原色钛金属","内存":"256GB","颜色英文":"Natural Titanium"}',
 8999.00, 100),
(2, 1, 'iPhone 15 Pro Max 512GB 原色钛金属',
 '{"颜色":"原色钛金属","内存":"512GB","颜色英文":"Natural Titanium"}',
 10999.00, 50);


-- ==========================================================
-- 3️⃣ 订单服务 (db_order)
-- ==========================================================

CREATE DATABASE IF NOT EXISTS `db_order` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE db_order;

-- 订单主表
CREATE TABLE IF NOT EXISTS `t_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `order_no` varchar(32) NOT NULL COMMENT '订单号',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `total_amount` decimal(10,2) NOT NULL COMMENT '订单总金额',
  `pay_amount` decimal(10,2) NOT NULL COMMENT '实付金额',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待支付 1-已支付 2-已发货 3-已完成 4-已取消',
  `pay_time` datetime DEFAULT NULL COMMENT '支付时间',
  `cancel_time` datetime DEFAULT NULL COMMENT '取消时间',
  `cancel_reason` varchar(255) DEFAULT NULL COMMENT '取消原因',
  `expire_time` datetime NOT NULL COMMENT '支付超时时间（下单后30分钟）',
  `remark` varchar(255) DEFAULT NULL COMMENT '订单备注',
  `deleted` tinyint NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单主表';

-- 订单明细表
CREATE TABLE IF NOT EXISTS `t_order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `sku_id` bigint NOT NULL COMMENT 'SKU ID',
  `sku_name` varchar(128) NOT NULL COMMENT 'SKU名称',
  `sku_image` varchar(256) DEFAULT NULL COMMENT 'SKU图片',
  `price` decimal(10,2) NOT NULL COMMENT '购买单价',
  `quantity` int NOT NULL COMMENT '购买数量',
  `subtotal` decimal(10,2) NOT NULL COMMENT '小计金额',
  `deleted` tinyint NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_sku_id` (`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细表';


-- ==========================================================
-- 4️⃣ 秒杀服务 (db_seckill)
-- ==========================================================

CREATE DATABASE IF NOT EXISTS `db_seckill` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE db_seckill;

-- 秒杀活动表
CREATE TABLE IF NOT EXISTS `t_seckill_activity` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '活动ID',
  `name` varchar(128) NOT NULL COMMENT '活动名称',
  `start_time` datetime NOT NULL COMMENT '开始时间',
  `end_time` datetime NOT NULL COMMENT '结束时间',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待开始 1-进行中 2-已结束',
  `deleted` tinyint NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_start_time` (`start_time`),
  KEY `idx_end_time` (`end_time`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀活动表';

-- 秒杀商品表
CREATE TABLE IF NOT EXISTS `t_seckill_product` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `activity_id` bigint NOT NULL COMMENT '活动ID',
  `sku_id` bigint NOT NULL COMMENT 'SKU ID',
  `seckill_price` decimal(10,2) NOT NULL COMMENT '秒杀价',
  `seckill_stock` int NOT NULL COMMENT '秒杀库存',
  `limit_per_user` int NOT NULL DEFAULT '1' COMMENT '每人限购数量',
  `deleted` tinyint NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_activity_sku` (`activity_id`,`sku_id`),
  KEY `idx_sku_id` (`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀商品表';

-- 初始数据
INSERT INTO `t_seckill_activity` (`id`, `name`, `start_time`, `end_time`, `status`) VALUES
(1, '618限时秒杀', DATE_ADD(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 2 DAY), 0);

INSERT INTO `t_seckill_product` (`activity_id`, `sku_id`, `seckill_price`, `seckill_stock`, `limit_per_user`) VALUES
(1, 1, 7999.00, 10, 1);

-- 秒杀订单表
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


-- ==========================================================
-- 5️⃣ 支付服务 (db_payment)
-- ==========================================================

CREATE DATABASE IF NOT EXISTS `db_payment` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE db_payment;

-- 支付流水表
CREATE TABLE IF NOT EXISTS `t_pay_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `pay_no` varchar(32) NOT NULL COMMENT '支付流水号',
  `order_no` varchar(32) NOT NULL COMMENT '订单号',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `pay_amount` decimal(10,2) NOT NULL COMMENT '支付金额',
  `pay_channel` tinyint NOT NULL COMMENT '支付渠道：1-支付宝 2-微信',
  `trade_no` varchar(64) DEFAULT NULL COMMENT '三方交易号（支付宝/微信返回）',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待支付 1-支付成功 2-支付失败 3-已退款',
  `pay_time` datetime DEFAULT NULL COMMENT '支付成功时间',
  `notify_time` datetime DEFAULT NULL COMMENT '回调通知时间',
  `deleted` tinyint NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_no` (`pay_no`),
  KEY `idx_order_no` (`order_no`),
  KEY `idx_trade_no` (`trade_no`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付流水表';
