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

### 创建订单

```bash
curl -s -X POST http://localhost:8080/api/order -H "Content-Type: application/json" -H "Authorization: Bearer %TOKEN%" -d "{\"items\":[{\"skuId\":1,\"quantity\":1}]}"
```

### 查询订单

```bash
curl -s http://localhost:8080/api/order/1 -H "Authorization: Bearer %TOKEN%"
```

```bash
curl -s "http://localhost:8080/api/order/page?page=1&size=10" -H "Authorization: Bearer %TOKEN%"
```

### 模拟支付

```bash
curl -s -X POST "http://localhost:8080/api/payment/notify" -H "Content-Type: application/x-www-form-urlencoded" -d "out_trade_no=PAY123456&trade_no=MOCK1680000000000&trade_status=TRADE_SUCCESS&total_amount=100.00"
```

### 取消订单

```bash
curl -s -X POST http://localhost:8080/api/order/1/cancel -H "Authorization: Bearer %TOKEN%" -d "reason=不想要了"
```

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

```bash
curl -s -X POST http://localhost:8080/api/seckill/warm-up/1 -H "Authorization: Bearer %TOKEN%"
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
