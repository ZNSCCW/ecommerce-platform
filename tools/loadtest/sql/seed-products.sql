-- ============================================================
-- 商品详情压测数据：1000 个 SPU + 1000 个 SKU（id 2001~3000）
-- ------------------------------------------------------------
-- 为什么需要：init.sql 只有 1 个 SPU。只压 1 个 id 时，
-- 「有缓存」测的是同一个 key 的极限命中率，「无缓存」也只有 1 行数据，
-- 缓存收益会被严重高估，也测不到 MySQL 的真实压力。
--
-- 用法：mysql -uroot -p123456 < sql/seed-products.sql
-- 幂等：INSERT IGNORE，重复执行不会报错。
-- ============================================================

SET SESSION cte_max_recursion_depth = 10000;

INSERT IGNORE INTO db_product.t_spu (id, name, brand, category_id, status)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 1000
)
SELECT 2000 + n,
       CONCAT('压测商品-', LPAD(n, 4, '0')),
       'BenchBrand',
       1,
       1
FROM seq;

INSERT IGNORE INTO db_product.t_sku (id, spu_id, name, specs, price, stock)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 1000
)
SELECT 2000 + n,
       2000 + n,
       CONCAT('压测商品-', LPAD(n, 4, '0'), ' 默认规格'),
       JSON_OBJECT('规格', '默认', '批次', n),
       99.00 + n,
       1000
FROM seq;

SELECT 'SPU 总数' AS item, COUNT(*) AS cnt FROM db_product.t_spu;
SELECT 'SKU 总数' AS item, COUNT(*) AS cnt FROM db_product.t_sku;
