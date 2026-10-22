# 压测方案

> 工具：`tools/loadtest/seckill_bench.py`（零依赖 Python，脚本化、可复现、自带超卖校验）。  
> 原则：**没跑出来的数字不进简历**。旧版本里那组「预期结果」是设计目标，不是实测值。

---

## 0. 旧版方案的三个设计缺陷（先看清楚，否则跑一天也是废数据）

| 旧做法 | 为什么无效 | 现在怎么做 |
|--------|-----------|-----------|
| 5000 并发共用同一个 `Authorization: Bearer {token}` | 秒杀接口按 `userId` 去重：`seckill:lock` / `seckill:ordered` / `seckill:limit` / DB `count(*)` 四道，同一个用户第 2 次起全部 `4003 请勿重复下单`。测出来的是拒绝路径的 TPS | 每个请求一个独立用户（用户池预注册） |
| `seckill_stock = 10`（或 100）对并发 5000 | 4900+ 请求在 Lua 里直接命中「库存不足」返回 `4004`，Redis 只执行了 100 次 DECRBY，MQ 一条都没发，链路根本没被压到 | 压测前把库存调到 ≥ 用户数，这样测的是「全部命中的极限吞吐」；再把库存设小跑一轮，测「抢光后的拒绝能力」 |
| 「无 MQ 削峰」对照场景 | 代码里只有 `rocketMQTemplate.syncSend` 一条路径，没有同步/异步开关，这个对照组跑不出来 | 删掉这个伪对照。削峰的收益用「MQ 堆积深度 / 消费延迟」讲，不用 TPS 讲 |

还有一个隐藏问题：**`POST /api/seckill` 每请求在扣 Redis 之前已经打了 3 次 DB**（查活动、查秒杀商品、`countByUserAndActivity`）。
所以 5000 并发下最先撑不住的几乎一定是 MySQL，而不是 Redis。压测时必须同时记录 DB 侧指标，否则会把 DB 瓶颈误读成「Redis 慢」。

---

## 1. 基线记录（不写这些，数字没有可比性）

| 项 | 值 |
|----|----|
| 机型 / CPU 核数 / 内存 | 待填 |
| JDK 版本 / 堆参数（`-Xms -Xmx`） | 待填 |
| MySQL / Redis / RocketMQ / ES 版本 | 待填 |
| Docker Desktop 资源限制（CPU / 内存） | 待填 |
| 压测器与被测服务是否同机 | 待填（同机会互相抢 CPU，绝对值偏低，只做相对比较） |
| Tomcat 线程数（默认 200）/ 网关实例数（默认 1） | 待填 |

---

## 2. 数据准备

```powershell
# 1) 活动置为进行中 + 库存调到压测量级 + 清历史订单
mysql -uroot -p123456 < tools/loadtest/sql/reset-seckill.sql

# 2) 商品详情场景需要 1000 条商品（init.sql 只有 1 条）
mysql -uroot -p123456 < tools/loadtest/sql/seed-products.sql

# 3) 清 Redis 秒杀残留（复用老用户池时必做）
docker exec ecommerce-redis sh -c "redis-cli --scan --pattern 'seckill:limit:*' | xargs -r redis-cli del"
docker exec ecommerce-redis sh -c "redis-cli --scan --pattern 'seckill:ordered:*' | xargs -r redis-cli del"

# 4) 预注册用户池（把 bcrypt 的注册成本从压测里摘出去）
python tools/loadtest/seckill_bench.py --users 5000 --pool demo --bootstrap-only
```

---

## 3. 场景一：商品详情 · 缓存对比

**目的**：量化 Redis 缓存对读多写少接口的收益，给出「提升倍数」而不是绝对值。

**协议**（每组 3 轮取中位数，避免一轮定生死）：

```powershell
# 冷缓存：每轮前清库
docker exec ecommerce-redis redis-cli FLUSHDB
python tools/loadtest/seckill_bench.py --scenario product --concurrency 64 --duration 30 --cold-cache

# 热缓存：热身 200 次后再计时
python tools/loadtest/seckill_bench.py --scenario product --concurrency 64 --duration 30
```

**记录**：

| 组 | 轮次 | QPS | Avg RT | P99 | 错误率 |
|----|------|-----|--------|-----|--------|
| 冷缓存 | 1/2/3 | | | | |
| 热缓存 | 1/2/3 | | | | |

**判据**：热组错误率 < 0.5%，且 QPS 提升倍数稳定；如果冷热差距很小，八成是缓存没配上或 key 每次不同（先 `redis-cli MONITOR` 看有没有 `GET product:...`）。

> ⚠️ `FLUSHDB` 会把秒杀库存一起清掉，商品场景测完要重新预热秒杀库存再跑场景二。

---

## 4. 场景二：秒杀突发（核心场景）

**目的**：找到单机部署下的容量拐点，并证明「不超卖」。

**并发梯度**：500 → 1000 → 3000 → 5000（每档改 `@stock` 与 `--users` 保持一致）。

```powershell
# 每一档前都要重置：库存 = 用户数、清 Redis、预热
mysql -uroot -p123456 < tools/loadtest/sql/reset-seckill.sql
python tools/loadtest/seckill_bench.py --scenario seckill --users 1000 --concurrency 300 --pool demo --reuse --stock 1000
```

**记录**（直接抄 `summary.md`）：

| 并发 | 总请求 | 成功 | 4003 重复 | 4004 售罄 | 5xx | TPS | P99 | 超卖校验 |
|------|--------|------|-----------|-----------|-----|-----|-----|----------|
| 500 | | | 0 | | | | | |
| 1000 | | | 0 | | | | | |
| 3000 | | | 0 | | | | | |
| 5000 | | | 0 | | | | | |

**有效性判据（缺一条这行数据就不能用）**：

1. `4003` 必须为 0 —— 不为 0 说明 token 被复用，压测设计错了，不是系统问题；
2. 成功数 = 库存消耗量，报告里是 `✅ 未超卖`；出现 `oversold` 才是真 bug（Lua 没原子 / 预热了两次）；
3. 错误率 < 1%，且错误集中在 `4004`（业务正确拒绝），而不是 `5xx` / 网络异常；
4. 梯度上 TPS 先升后平、P99 开始翻倍的位置 = **容量拐点**，这才是「这套部署能扛多少」的答案。

**补充观测**（讲削峰时必须带上）：

- RocketMQ Dashboard（:8088）看 `seckill-order-topic` 的堆积量与消费 TPS，证明「削峰」确实把瞬时流量摊平了；
- `SELECT COUNT(*) FROM db_seckill.t_seckill_order` 与成功数对齐；
- MySQL `SHOW GLOBAL STATUS LIKE 'Questions'`，若 ≈ 请求数 × 3，说明活动/商品配置查询没被挡住，这是第一个该优化的点。

---

## 5. 场景三：混合流量

**目的**：模拟真实用户行为，看整体吞吐与相互影响（秒杀把 DB / MQ 打满时，商品浏览是否被拖慢）。

```powershell
python tools/loadtest/seckill_bench.py --scenario mixed --concurrency 200 --duration 60 --users 2000
```

| 接口 | 权重 | 说明 |
|------|------|------|
| `GET /api/product/spu/{id}` | 50% | 缓存命中路径 |
| `POST /api/product/search` | 20% | ES 搜索 |
| `POST /api/seckill` | 15% | 秒杀（用户 token 用完后退化为浏览） |
| `POST /api/user/login` | 10% | 登录 |
| `GET /api/product/spu/page` | 5% | 列表 |

记录总 TPS、整体 P99，以及**分接口 P99**（`raw.csv` 里按 bucket 拆）——混合场景的价值就在「秒杀拖慢了多少浏览」。

---

## 6. 结果记录模板

`summary.md` 已自动生成下面这行，复制到 README「压测数据」表即可：

```markdown
| 场景 | 并发 | QPS | P99 RT | 错误率 | 备注 |
|------|------|-----|--------|--------|------|
| 秒杀下单（MQ 削峰） | 1000 | 待实测 | 待实测 | 待实测 | 本机单实例，同机压测 |
```

填表前再确认一次：这行数字对应的 `summary.json` 里 `4003 = 0`、`oversold = false`。

---

## 7. 已知瓶颈与优化预判（读源码得出，压测时逐条验证）

| # | 瓶颈 | 代码位置 | 验证方法 | 可能的优化 |
|---|------|---------|---------|-----------|
| 1 | 每请求 3 次 DB 查询（活动、秒杀商品、`count(*)`） | `SeckillService.seckill` 步骤 1/2/4 | MySQL Questions 增速 ≈ 请求数 × 3 | 活动+商品配置进 Caffeine 本地缓存；`count(*)` 改成 Redis 计数 |
| 2 | `syncSend` 在请求线程里同步等 MQ 回执 | `SeckillService` 步骤 7 | MQ Dashboard 堆积上升时 RT 同步上升 | 改 `asyncSend` + 本地失败重试表 |
| 3 | Redisson `tryLock` 每请求一次往返，同 key 竞争 | 步骤 3 | 并发越高，`seckill:lock` 争抢越明显 | 限购已由 Lua 保证，锁可退化为 `SETNX` 短 TTL |
| 4 | 单实例网关 + Tomcat 默认 200 线程 | gateway | 并发 > 300 后 P99 陡增、TPS 不再涨 | 加实例 / 调 `server.tomcat.threads.max` |
| 5 | 秒杀链路的 `@RequestAttribute` + Security 过滤器链开销 | `JwtAuthFilter` | 对比直连 8084 与走 8080 的 RT 差 | 网关统一鉴权，下游服务内网直连免鉴权 |

> 面试里说「瓶颈在 DB 的配置查询」比说「TPS 3000」值钱得多 —— 前者证明你真的看过链路。

---

## 8. 附录：JMeter 等价配置（要交 JMeter 版时用）

如果面试方要求 JMeter 报告，按下面配，**仍然要遵守第 0 节的三条修正**：

1. **用户**：`CSV Data Set Config` 读取预生成的 5000 行 `token,username`，`Recycle on EOF = false`；
2. **线程组**：线程数 = 并发（建议 300），Ramp-up = 0，循环 = 1，勾选 `Same user on each iteration`（避免循环内换 token）；
3. **HTTP 请求**：`POST /api/seckill`，Body `{"activityId":1,"skuId":1}`，Header `Authorization: Bearer ${token}`；
4. **断言**：`JSON Assertion` 断言 `$.code == 200` 且 `$.data.code == 1`，否则把 4003 也当成成功统计；
5. **监听器**：只留 `Summary Report` + `Aggregate Report`，压测时**必须**用非 GUI：
   `jmeter -n -t seckill.jmx -l result.jtl -e -o report/`；
6. 压测结束后用第 4 节的四条判据检查，`result.jtl` 里 `success=false` 的响应码分布就是错误分布。

---

## 9. 面试口径

- **真的跑过**：报 `并发 / TPS / P99 / 错误率`，并主动说出「同一台机器上压的，绝对值不代表生产，看的是缓存前后、拐点位置」；
- **没跑成**：只说设计（「每个用户一个 token、库存要 ≥ 并发、要校验超卖」），
  不报数字。能讲清「为什么不能拿同一个 token 压 5000 并发」本身就是加分项。
