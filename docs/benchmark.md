# 压测方案

> 使用 Apache JMeter 对核心接口进行压力测试，采集 QPS、RT、错误率等数据用于简历。

---

## 环境准备

1. 下载 JMeter：https://jmeter.apache.org/download_jmeter.cgi
2. 确保所有服务 + 中间件已启动
3. 确保 `db_product` 中有足够测试数据（至少 1000+ 商品）

---

## 场景 1：商品详情 — 缓存对比

### 目的
对比有缓存 vs 无缓存的 RT 和 QPS，量化 Redis 缓存效果。

### 操作步骤

1. 打开 JMeter，创建测试计划
2. 添加线程组：
   - 线程数：500
   - Ramp-up：5s
   - 循环：100
3. 添加 HTTP 请求：
   - 协议：http
   - 服务器：localhost
   - 端口：8080
   - 路径：`/api/product/spu/{随机ID 1-1000}`
   - 方法：GET
4. 添加监听器：聚合报告、响应时间图

### 第一次（无缓存）
先清空 Redis：
```bash
redis-cli FLUSHDB
```
运行压测，记录 QPS 和平均 RT。

### 第二次（有缓存）
再次运行压测（缓存已建立），记录 QPS 和平均 RT。

### 预期结果
```
无缓存：QPS ~200,  RT ~50ms
有缓存：QPS ~1500, RT ~5ms
提升约 7-10 倍
```

---

## 场景 2：秒杀下单 — 削峰对比

### 目的
验证 RocketMQ 异步削峰效果，对比同步 vs 异步的 TPS。

### 准备
1. 先调用预热接口：
```bash
curl -X POST http://localhost:8080/api/seckill/warm-up/1 \
  -H "Authorization: Bearer {登录后获取的Token}"
```

2. 确保活动状态为进行中（`db_seckill.t_seckill_activity.status=1`），如不是则手动更新：
```sql
UPDATE db_seckill.t_seckill_activity SET status = 1,
  start_time = DATE_SUB(NOW(), INTERVAL 1 HOUR),
  end_time = DATE_ADD(NOW(), INTERVAL 1 DAY)
WHERE id = 1;
```

3. 重置秒杀库存：
```sql
UPDATE db_seckill.t_seckill_product SET seckill_stock = 100 WHERE id = 1;
```

### JMeter 配置

1. 添加线程组：
   - 线程数：1000 ~ 5000
   - Ramp-up：3s
   - 循环：1

2. 添加 HTTP 请求（秒杀）：
   - 路径：`POST /api/seckill`
   - Body：`{"activityId":1,"skuId":1}`
   - Header：`Authorization: Bearer {token}`

3. 使用 CSV 或函数助手生成不同用户的 Token（或用 JSR223 预处理获取新 Token）

### 预期结果
```
并发 1000：TPS ~800,  错误率 < 1%
并发 3000：TPS ~2000, 错误率 < 2%
并发 5000：TPS ~3000, 错误率 < 5%
零超卖验证：库存扣减总数 = 数据库订单总数
```

---

## 场景 3：全链路混合压测

### 目的
模拟真实用户行为（浏览 + 搜索 + 下单），测试系统整体吞吐量。

### JMeter 配置

| 接口 | 流量占比 | 说明 |
|------|---------|------|
| GET `/api/product/spu/{id}` | 50% | 商品浏览（缓存命中） |
| POST `/api/product/search` | 20% | ES 搜索 |
| POST `/api/seckill` | 15% | 秒杀下单 |
| POST `/api/user/login` | 10% | 登录 |
| GET `/api/product/spu/page` | 5% | 商品列表 |

### 线程组配置
- 线程数：2000
- Ramp-up：10s
- 循环：50

---

## 记录模板

| 场景 | 并发 | QPS | Avg RT | P99 RT | 错误率 | 备注 |
|------|------|-----|--------|--------|--------|------|
| 商品详情(无缓存) | 500 | | | | | |
| 商品详情(有缓存) | 500 | | | | | |
| 秒杀(order2->pay) | 1000 | | | | | |
| 秒杀(无MQ→同步) | 1000 | | | | | 对比用 |
| 全链路混合 | 2000 | | | | | |

> 跑完压测后，将真实数据填入 README.md 的压测数据表格和 docs/resume.md。
