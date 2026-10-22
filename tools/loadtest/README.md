# 压测工具（零依赖）

> 针对 `docs/benchmark.md` 原方案里三个「跑出来也没意义」的问题写的替代方案。
> 只用 Python 标准库，不装 JMeter、不装 Locust、不开 GUI。

## 为什么不用原方案

| 原方案 | 后果 | 这里怎么改 |
|--------|------|-----------|
| 5000 并发共用 1 个 token 打秒杀 | `SeckillService` 按 userId 去重（lock / ordered / limit / DB count 四道），999‰ 请求返回 `4003 请勿重复下单`，测到的是拒绝路径 | 每个请求用一个独立用户，一人一枪 |
| 库存 10 / 100，并发 5000 | 4900 个请求命中 `4004 库存已被抢光`，Redis+Lua+MQ 根本没被压到 | 压测前把库存调到 ≥ 用户数（`sql/reset-seckill.sql`） |
| ramp-up 5s + 循环 100 次 | 测出来的是稳态吞吐，不是秒杀要的瞬时突发；线程数≠并发数 | `threading.Barrier` 闸门，所有线程同时释放 |

另外：**压完自动校验超卖**（成功数 = 库存消耗量），这是秒杀压测最该看的一条。

## 前置条件

```powershell
docker compose ps            # redis / nacos / rmq-namesrv / rmq-broker 都是 Up
curl http://localhost:8080/api/seckill/activities
```

服务没起来就先跑 `start-all.bat`；Nacos 里 5 个服务注册成功再压。

> Windows 控制台中文乱码时先执行 `chcp 65001`。

---

## 场景 A：秒杀链路

### 1. 重置数据（把库存调到压测量级）

```powershell
mysql -uroot -p123456 < tools/loadtest/sql/reset-seckill.sql
```

先改脚本第 14 行的 `@stock`，**必须 ≥ 用户数**，否则多出来的请求会变成 4004。
脚本同时会把活动置为「进行中」、清掉历史订单。

### 2. 清 Redis 里的秒杀残留（复用老用户池时必须做）

```powershell
docker exec ecommerce-redis sh -c "redis-cli --scan --pattern 'seckill:limit:*' | xargs -r redis-cli del"
docker exec ecommerce-redis sh -c "redis-cli --scan --pattern 'seckill:ordered:*' | xargs -r redis-cli del"
```

用新 pool（每轮新用户）可以跳过，脚本默认就是新 pool。

### 3. 先备用户，再压（推荐：把注册成本从压测里摘出去）

```powershell
python tools/loadtest/seckill_bench.py --users 5000 --pool demo --bootstrap-only
```

5000 个用户要过 bcrypt，本机大约 30~60s，一次就好。

### 4. 正式压测

```powershell
python tools/loadtest/seckill_bench.py --scenario seckill --users 5000 --concurrency 500 --pool demo --reuse --stock 5000
```

- `--users`：独立用户数 = 请求数
- `--concurrency`：同时起跑的线程数（建议 200~500；再高是拿压测器 CPU 换被测服务 CPU）
- `--pool demo --reuse`：复用上一步的用户，重新登录刷新 token
- `--stock 5000`：用于校验，写错会提示

### 5. 看结果

输出在 `tools/loadtest/results/<时间戳>-seckill/`：

| 文件 | 内容 |
|------|------|
| `summary.md` | 人读版报告，含可直接粘进 README 的一行 |
| `summary.json` | 机读版，便于多次结果对比 |
| `raw.csv` | 每条请求的耗时与结果，可画图 |

**有效性自检（三条都过，数字才能写进简历）**：

1. `4003 请勿重复下单` 为 0 —— 不为 0 说明有 token 被复用，压测设计有问题；
2. `错误率 < 1%`，且报错集中在 `4004 库存已被抢光`（这是业务正确拒绝）；
3. `成功数 = 库存消耗量`，`✅ 未超卖`。

---

## 场景 B：商品详情 · 缓存对比

```powershell
# 灌 1000 条商品（init.sql 只有 1 条，只压 1 个 id 会把缓存收益放大）
mysql -uroot -p123456 < tools/loadtest/sql/seed-products.sql

# 冷缓存组：先清缓存再压（⚠️ FLUSHDB 会连秒杀库存一起清，压完记得重新预热）
docker exec ecommerce-redis redis-cli FLUSHDB
python tools/loadtest/seckill_bench.py --scenario product --concurrency 64 --duration 30 --cold-cache

# 热缓存组：同一个脚本，热身 200 次后再计时
python tools/loadtest/seckill_bench.py --scenario product --concurrency 64 --duration 30
```

两组各跑 3 轮，取中位数，别拿一次结果下结论。

---

## 场景 C：混合流量

```powershell
python tools/loadtest/seckill_bench.py --scenario mixed --concurrency 200 --duration 60 --users 2000
```

权重默认 `spu 50% / search 20% / seckill 15% / page 10% / login 5%`，在脚本里 `Cfg.weights` 改。

---

## 怎么读数字

- **QPS 不是越高越好，要看对应的 RT 和错误率**。并发 ≈ QPS × RT，三个数对不上就是采样有问题。
- 同一台机器上压测器和被测服务抢 CPU，绝对数字偏小 —— 这没关系，**缓存前后、削峰前后的相对倍数才是结论**。
- 错误率 > 5% 先看分布，不要直接贴 TPS：`4004` 是业务拒绝，`401` 是 token 过期，`5xx`/`网络异常` 才是真问题。
- 秒杀这条链路每个请求在扣 Redis 之前还有 2 次 DB 查询（活动、商品）+ 1 次 `count(*)`，
  所以 TPS 天花板很可能在 MySQL 而不是 Redis。压的时候开 `SHOW GLOBAL STATUS LIKE 'Questions'`
  或 `SHOW PROCESSLIST` 对照，DB QPS ≈ 请求数 × 3 就说明读没被挡住。

## 常见问题

| 现象 | 原因 | 处理 |
|------|------|------|
| 大量 `4003 请勿重复下单` | token 复用 / Redis 里 `seckill:limit:*` 没清 | 换新 pool，或按上面命令清 key |
| 大量 `4004 库存已被抢光` | 库存 < 用户数 | 调大 `@stock` 后重新跑 SQL + 预热 |
| 大量 `401` | 用户池的 token 过期 | 加 `--reuse` 重新登录，或换新 pool |
| 全部 `网络异常` | 网关没起 / 端口不对 | 先 `curl http://localhost:8080/api/seckill/activities` |
| 库存校验 `mismatch` 但没超卖 | MQ 消费慢，或 MQ 发送吞了异常 | 调大 `--verify-wait`，看 RocketMQ Dashboard 有无堆积 |
| 商品详情全是 `2001 商品不存在` | 只跑了 init.sql，没灌测试商品 | 跑 `sql/seed-products.sql` |

## 面试口径

这套工具跑出来的数字可以直接说「实测」，因为报告里带了时间、并发、错误分布和超卖校验。
如果某轮没跑成（环境没起、数据没备），**宁可不报数字**，也不要拿 `docs/benchmark.md` 里的
“预期结果”当实测讲 —— 面试官追问一句“P99 多少、当时什么机型和并发”就穿帮了。
