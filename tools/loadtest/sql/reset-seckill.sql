-- ============================================================
-- 秒杀压测前置：活动置为进行中 + 库存调到压测量级 + 清历史订单
-- ------------------------------------------------------------
-- 用法：
--   mysql -uroot -p123456 < sql/reset-seckill.sql
--   或 Navicat / DataGrip 里整段执行（会按 ; 分句，@stock 变量需同一会话）
--
-- ⚠️ 会清空 t_seckill_order / t_order / t_order_item / t_pay_record，
--    演示数据请先备份。
-- ============================================================

SET @stock = 5000;   -- ← 改成你的压测用户数（--users），必须 >= 用户数

UPDATE db_seckill.t_seckill_activity
SET status     = 1,
    start_time = DATE_SUB(NOW(), INTERVAL 1 HOUR),
    end_time   = DATE_ADD(NOW(), INTERVAL 1 DAY)
WHERE id = 1;

-- limit_per_user 保持 1：真实秒杀语义就是一人一单，
-- 想用少量用户打高吞吐可以把它调大，但那就不是秒杀了，面试别拿来当数据。
UPDATE db_seckill.t_seckill_product
SET seckill_stock  = @stock,
    limit_per_user = 1
WHERE activity_id = 1 AND sku_id = 1;

-- 清掉上一轮的业务数据，保证「从零可重测」
DELETE FROM db_seckill.t_seckill_order;
DELETE FROM db_payment.t_pay_record;
DELETE FROM db_order.t_order_item;
DELETE FROM db_order.t_order;

-- 上面两句有外键时按此顺序执行；如报外键错误，先 SET FOREIGN_KEY_CHECKS=0;

SELECT '活动' AS item, status, start_time, end_time
FROM db_seckill.t_seckill_activity WHERE id = 1;
SELECT '库存' AS item, seckill_stock, limit_per_user
FROM db_seckill.t_seckill_product WHERE activity_id = 1;
SELECT '残留秒杀订单' AS item, COUNT(*) AS cnt FROM db_seckill.t_seckill_order;
