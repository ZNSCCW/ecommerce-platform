# 功能测试流程

> 所有请求通过 Gateway（`http://localhost:8080`）。  
> 所有 curl 命令均写在一行，可直接复制到 CMD 执行。

测试前确保：
- 中间件已启动：`docker compose up -d`
- 所有服务已启动：`start-all.bat`（或逐个 java -jar）
- 数据库已初始化：Navicat 运行 `docs/init.sql`

---

## 1. 注册与登录

```bash
curl -s -X POST http://localhost:8080/api/user/register -H "Content-Type: application/json" -d "{\"username\":\"alice\",\"password\":\"123456\"}"
```

```bash
curl -s -X POST http://localhost:8080/api/user/login -H "Content-Type: application/json" -d "{\"username\":\"alice\",\"password\":\"123456\"}"
```

返回结果：
```json
{"code":200,"msg":"success","data":{"accessToken":"eyJ...","refreshToken":"eyJ...","userId":2,"username":"alice"}}
```

保存 Token（CMD）：
```cmd
set TOKEN=eyJ...（粘贴 accessToken）
```

### 越权防护验证

```bash
curl -s http://localhost:8080/api/user/3 -H "Authorization: Bearer %TOKEN%"
```
应返回 403（alice 不能查 bob 的信息）。

### 刷新 Token

```bash
curl -s -X POST http://localhost:8080/api/user/refresh -H "Content-Type: application/json" -d "{\"refreshToken\":\"粘贴refreshToken\"}"
```

---

## 2. 商品浏览

```bash
curl -s "http://localhost:8080/api/product/spu/page?page=1&size=10"
```

```bash
curl -s http://localhost:8080/api/product/spu/1
```

```bash
curl -s http://localhost:8080/api/product/category/tree
```

### ES 搜索

```bash
curl -s -X POST http://localhost:8080/api/product/search -H "Content-Type: application/json" -d "{\"keyword\":\"iPhone\",\"page\":1,\"size\":10}"
```

```bash
curl -s -X POST http://localhost:8080/api/product/search -H "Content-Type: application/json" -d "{\"keyword\":\"iPhone\",\"minPrice\":5000,\"maxPrice\":10000,\"sortBy\":\"price_asc\"}"
```

---

## 3. 订单流程

### 创建订单（记为订单 A）

```bash
curl -s -X POST http://localhost:8080/api/order -H "Content-Type: application/json" -H "Authorization: Bearer %TOKEN%" -d "{\"items\":[{\"skuId\":1,\"quantity\":1}]}"
```

返回 `data.id` 为订单 A 的 id、`data.orderNo` 为订单号。

### 查询订单（订单 A）

```bash
curl -s http://localhost:8080/api/order/<订单A的id> -H "Authorization: Bearer %TOKEN%"
```

```bash
curl -s "http://localhost:8080/api/order/page?page=1&size=10" -H "Authorization: Bearer %TOKEN%"
```

### 取消订单（订单 A，须在待支付状态）

> ⚠️ 状态机只允许「待支付」订单取消；**已支付订单取消会返回 `3002 订单状态异常`**（退款属另一流程）。取消成功会 Feign 恢复库存。

```bash
curl -s -X POST http://localhost:8080/api/order/<订单A的id>/cancel -H "Authorization: Bearer %TOKEN%" -d "reason=不想要了"
```

### 再创建订单（记为订单 B，用于支付演示）

> 订单 A 已取消，支付演示需要一笔「待支付」订单，因此再下一单。

```bash
curl -s -X POST http://localhost:8080/api/order -H "Content-Type: application/json" -H "Authorization: Bearer %TOKEN%" -d "{\"items\":[{\"skuId\":1,\"quantity\":1}]}"
```

### 模拟支付（订单 B）

> 回调必须带 `createPay` 签发的 HMAC 签名（sign 覆盖 payNo|tradeNo|trade_status|total_amount），否则验签失败返回 `failure`。

**Step 1：创建支付单**（接口是 `POST /api/payment`，不是 /create；orderNo 填订单 B 的订单号）

```bash
curl -s -X POST http://localhost:8080/api/payment -H "Content-Type: application/json" -H "Authorization: Bearer %TOKEN%" -d "{\"orderNo\":\"<订单B的orderNo>\",\"amount\":8999.00}"
```

返回 `data.payForm` 是一个 HTML 表单，里面含 `out_trade_no / trade_no / trade_status / total_amount / sign` 五个字段。

**Step 2：模拟回调**（把 payForm 里的值填进来）

```bash
curl -s -X POST "http://localhost:8080/api/payment/notify" -H "Content-Type: application/x-www-form-urlencoded" -d "out_trade_no=<payNo>&trade_no=<tradeNo>&trade_status=TRADE_SUCCESS&total_amount=<金额>&sign=<sign>"
```

返回 `success` 后订单 B 状态变为「已支付」（幂等：重复回调返回 success 不重复处理）。

---

## 4. 秒杀测试

### 准备数据

在 Navicat 中执行：
```sql
UPDATE db_seckill.t_seckill_activity
SET status = 1,
    start_time = DATE_SUB(NOW(), INTERVAL 1 HOUR),
    end_time = DATE_ADD(NOW(), INTERVAL 1 DAY)
WHERE id = 1;

UPDATE db_seckill.t_seckill_product SET seckill_stock = 10 WHERE id = 1;
```

### 预热库存

> ⚠️ warm-up 接口限 ADMIN/SUPER_ADMIN（防任意用户刷满库存），**必须用管理员账号登录**拿 token：

```bash
curl -s -X POST http://localhost:8080/api/user/login -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"<DEFAULT_ADMIN_PASSWORD>\"}"
```

```bash
curl -s -X POST http://localhost:8080/api/seckill/warm-up/1 -H "Authorization: Bearer <管理员Token>"
```

### 查看活动与库存

```bash
curl -s http://localhost:8080/api/seckill/activities
```

```bash
curl -s http://localhost:8080/api/seckill/stock/1/1
```

### 秒杀

```bash
curl -s -X POST http://localhost:8080/api/seckill -H "Content-Type: application/json" -H "Authorization: Bearer %TOKEN%" -d "{\"activityId\":1,\"skuId\":1}"
```

预期返回：
```json
{"code":200,"msg":"success","data":{"code":1,"msg":"正在排队，请稍后查看订单结果"}}
```

重复秒杀应返回"请勿重复下单"。

### 验证秒杀订单（MQ 异步建单）

秒杀返回"排队中"后，等几秒让 RocketMQ 消费者异步建单，再查库：

```sql
SELECT id, order_no, user_id, activity_id, sku_id, seckill_price, status FROM db_seckill.t_seckill_order;
```

秒杀订单 `status=0`（待支付），同时商品真实库存被 Feign 扣减。

### 重复测试注意事项

> ⚠️ 秒杀成功后 Redis 会写入 `seckill:ordered:{activityId}:{skuId}:{userId}`（30 分钟 TTL）和 `seckill:limit:{...}`（活动时长 TTL）。**跨轮次重复实测前必须清理**，否则同一用户会一直命中"请勿重复下单"：

```bash
docker exec ecommerce-redis redis-cli DEL "seckill:ordered:1:1:2" "seckill:limit:1:1:2"   # 2 = alice 的 userId
```

同时清理 DB 里的秒杀订单与普通订单，保证从零重测：
```sql
DELETE FROM db_seckill.t_seckill_order;
DELETE FROM db_order.t_order_item; DELETE FROM db_order.t_order;
DELETE FROM db_payment.t_pay_record;
```

---

## 5. 监控面板

| 地址 | 账号 | 用途 |
|------|------|------|
| `http://localhost:8090` | admin / admin123 | Spring Boot Admin |
| `http://localhost:8848/nacos` | — | 服务注册列表 |
| `http://localhost:8088` | — | RocketMQ 消息管理 |
| `http://localhost:5601` | — | Kibana（ES 可视化） |

---

## 常见问题

| 现象 | 原因 | 解决 |
|------|------|------|
| 401 未登录 | Token 过期或未传 | 重新登录，检查 `Authorization: Bearer {token}` 格式 |
| 503 Service Unavailable | Nacos 未启动或服务未注册 | `docker compose ps` 检查 Nacos，等服务注册完成 |
| 500 服务器内部错误 | MySQL 连不上或表未建 | 检查 MySQL 服务，运行 `init.sql` |
| 秒杀返回排队中但无订单 | RocketMQ 消费者未处理 | 检查 RocketMQ Dashboard 是否有消息堆积 |
| 商品列表为空 | `init.sql` 未执行 | 运行 SQL 脚本，确认 `t_spu` 表有数据 |
