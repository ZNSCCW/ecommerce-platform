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
| 机型 / CPU 核数 / 内存 | ASUS TUF Gaming A15 FA507NV / 16 核 / 16 GB |
| JDK 版本 / 堆参数（`-Xms -Xmx`） | Oracle JDK 21.0.11；被测服务压到 `-Xms128m -Xmx256m -XX:MaxMetaspaceSize=192m`（为给 Docker 留内存，非默认） |
| MySQL / Redis / RocketMQ / ES 版本 | MySQL 8.4（本机安装）、Redis 7.0、RocketMQ 5.1.4、ES 7.17.18（本轮未启动） |
| Docker Desktop 资源限制（CPU / 内存） | WSL2 后端，测试期临时 `.wslconfig`：`memory=3GB / swap=1GB / processors=8` |
| 压测器与被测服务是否同机 | 是：Python 200 线程 + 4 个 JVM + Docker VM 抢同一台 16 核 16G |
| Tomcat 线程数（默认 200）/ 网关实例数（默认 1） | 200（默认值，未调）/ 1 |

> 以上是 2026-10-22 那轮实测的机器基线，与 README「压测数据」一节的说明一致。

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
# 冷缓存：每轮前清库（注意 spu 范围要和 seed-products.sql 灌的 id 对齐）
docker exec ecommerce-redis redis-cli FLUSHDB
python tools/loadtest/seckill_bench.py --scenario product --concurrency 64 --duration 30 --cold-cache --spu-min 2001 --spu-max 3000

# 热缓存：热身 200 次后再计时
python tools/loadtest/seckill_bench.py --scenario product --concurrency 64 --duration 30 --spu-min 2001 --spu-max 3000
```

**实测（2026-10-22，64 线程，1000 条商品随机 id）**：

| 组 | QPS | Avg RT | P95 | P99 | 错误率 |
|----|-----|--------|-----|-----|--------|
| 缓存 bug 态（修复前，见 §7） | 99 | 642 ms | 719 ms | 1139 ms | 0% |
| 冷缓存 | 1618 | 39.5 ms | 67.8 ms | 576 ms | 0% |
| 热缓存 | 2385 | 26.8 ms | 33.5 ms | 44 ms | 0% |

冷缓存这一列不是「无缓存」：首访回源后缓存即生效，指标被前半段回源拉低，所以 P99 576ms。
真正的「无缓存」对照是「缓存 bug 态」那一行 —— 它同时命中率 0 且每请求 2.4 次加锁，
是故障态而不是正常的无缓存基线（正常无缓存至少不该有分布式锁开销）。

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

**实测（2026-10-22，5000 用户 / 库存 5000，单机）**：

| 并发 | 总请求 | 成功 | 4003 重复 | 5xx | TPS | Avg RT | P99 | 超卖校验 |
|------|--------|------|-----------|-----|-----|--------|-----|----------|
| 300（Hikari=10，StdOutImpl 开着） | 5000 | 5000 | 0 | 0 | 79.3 | 3718 ms | 6837 ms | ✅ 5000 = 库存消耗 |
| 200（Hikari=30） | 5000 | 5000 | 0 | 0 | 89.0 | 2235 ms | 3579 ms | ✅ 5000 = 库存消耗 |
| 200（Hikari=80） | 5000 | 5000 | 0 | 0 | 88.3 | 2252 ms | 3222 ms | ✅ 5000 = 库存消耗 |
| 200（关 StdOutImpl，Hikari=30） | 5000 | 5000 | 0 | 0 | **447** | 443 ms | 2632 ms | ✅ 5000 = 库存消耗 |
| 200（关 StdOutImpl，复测） | 5000 | 5000 | 0 | 0 | **602** | 331 ms | 669 ms | ✅ 5000 = 库存消耗 |
| 200（再关 Redisson 锁） | 5000 | 5000 | 0 | 0 | 440 | 450 ms | 2481 ms | ✅ 5000 = 库存消耗 |
| 200（+ 配置本地缓存，DB 查询 3→0） | 5000 | 5000 | 0 | 0 | 577 | 339 ms | 589 ms | ✅ 5000 = 库存消耗 |
| 200（配置缓存复测） | 5000 | 5000 | 0 | 0 | 635 | 312 ms | 478 ms | ✅ 5000 = 库存消耗 |
| 200（配置缓存 + 关锁） | 5000 | 5000 | 0 | 0 | 545 | 362 ms | 2074 ms | ✅ 5000 = 库存消耗 |
| 200（+ Outbox 异步投递） | 5000 | 5000 | 0 | 0 | 449 | 441 ms | 1774 ms | ✅ outbox 5000 条全 success |
| 200（Outbox 复测） | 5000 | 5000 | 0 | 0 | 677 | 291 ms | 458 ms | ✅ 同上 |

结论：**瓶颈是 MyBatis 的 stdout SQL 打印，不是连接池、不是分布式锁、也不是 DB 查询次数**。
Hikari 10→80 只把 P99 从 6.8s 砍到 3.2s、吞吐不动；关掉 StdOutImpl 后吞吐 5 倍；
此后把热点路径 DB 查询从 3 次降到 0 次（配置本地缓存）吞吐仍在 550–635 波动。
下一步要往 MQ 消费端与单实例网关/客户端排队方向查，而不是继续减 SQL。

**读这张表要注意三点**：

1. **上表是同一条链路改不同配置的对比，不是并发梯度**（梯度在下面单独一节，固定 5000 用户只变并发）；
   所以不能拿上表说「找到了容量拐点」。
2. **300 那档和 200 档不能直接对比**：300 档是旧版本（StdOutImpl 还开着、Hikari=10），
   200 档是关日志之后的版本，混了多个变量，只能各自当作「优化前/后」的参考点。
3. 表里没有「5000 消耗 vs 4998 成功」那轮（真实 SKU 库存耗尽导致 2 单无订单）——
   那是 Outbox 之前的旧版本现象，已在 §7.4 的 Outbox 版本修复。

每行都是 0 错误、0 重复、5000 条订单全部落库（`SELECT COUNT(*) FROM db_seckill.t_seckill_order` = 5000）。

**并发梯度（2026-10-22 补测）**：固定 5000 用户、库存 5000，只改并发线程数，每档前重置数据与 Redis。

| 并发 | 总请求 | 成功 | 4003 重复 | 4004 售罄 | 错误率 | TPS | 相比上一档 | Avg RT | P95 | P99 | 超卖校验 |
|------|--------|------|-----------|-----------|--------|-----|-----------|--------|-----|-----|----------|
| 100 | 5000 | 5000 | 0 | 0 | 0% | 1109.7 | — | 89.7 ms | 119.2 ms | 229.3 ms | ✅ 5000 = 库存消耗 |
| 200 | 5000 | 5000 | 0 | 0 | 0% | 1214.2 | +9.4% | 163.3 ms | 209.3 ms | 268.9 ms | ✅ 5000 = 库存消耗 |
| 400 | 5000 | 5000 | 0 | 0 | 0% | 1235.9 | +1.8% | 310.5 ms | 344.1 ms | 357.0 ms | ✅ 5000 = 库存消耗 |
| 600 | 5000 | 5000 | 0 | 0 | 0% | 1242.0 | +0.5% | 460.9 ms | 517.8 ms | 556.8 ms | ✅ 5000 = 库存消耗 |

**这组数据说明什么**：

- 边际收益：100→200 还有 **+9.4%**，200→400 只剩 **+1.8%**，400→600 只剩 **+0.5%**；
  而 Avg RT 从 90ms 线性涨到 461ms —— **拐点在 200 附近**，400 只是「彻底走平」的位置，
  再往上加并发只是加排队；
- 单机有效容量约 **1.2k TPS**（这套精简环境、这条链路）；
- 四档都是 0 错误、0 重复、0 售罄，5000 单落库，0 超卖。

**Little's Law 自查**：用 `并发 / Avg RT` 反算 TPS ——

| 并发 | 反算 TPS | 实测 TPS | 偏差 |
|------|---------|---------|------|
| 100 | 1115 | 1109.7 | 0.5% |
| 200 | 1225 | 1214.2 | 0.9% |
| 400 | 1288 | 1235.9 | 4.2% |
| 600 | 1302 | 1242.0 | 4.8% |

低并发吻合得很好，高并发偏差约 4–5%，主要来自压测客户端 600 个 Python 线程的调度开销与统计窗口，
不影响容量结论。

> ⚠️ **这组和前一张表不能混用**：梯度是在精简环境（只起 redis / nacos / rmq，未起 ES / Kibana / dashboard，
> 内存更宽裕）下测的，绝对值高于前面带全量中间件、内存只剩 1.5G 时的那一系列（447–677 TPS）。
> 具体是「内存」还是「ES/Kibana 的 CPU 占用」贡献更大，没做隔离实验，**只是推断**。
> 同一组内的四档才可比。想复现这张表：只启动 redis / nacos / rmq 四个容器，
> 用 `tools/loadtest/README.md` 里的命令，固定 `--users 5000`、改 `--concurrency`。

**一次未能定因的失败（如实记录）**：最初按「N 用户 = N 线程」跑 500 并发，出现 36–40 个 HTTP 500。

- 特征：响应体是 Spring 默认错误页（没有业务 `code`）、客户端侧没有「网络异常」记录、
  库存未被扣减，网关日志是 `Connection refused: getsockopt` —— 请求死在「网关 → 秒杀服务」建连阶段；
- 不是业务逻辑问题，也不是客户端连网关失败（那种会进 `connect_errors` / 网络异常桶）；
- **原因未定位**：当时已经是精简环境（只有 4 个容器，内存不紧张），
  且后续 100/200/400/600 四档都没有复现；冷启动 JIT/GC、瞬时 accept 队列都可能，
  不做单一归因。梯度最终改成「固定 5000 用户、只变并发（≤600）」，四档稳定 0 错误。

**下次怎么定案（现在不追，先把方法留下）**：`seckill_bench.py` 已支持记录每条请求的时间戳
（`raw.csv` 的 `ts` 列），并在 `summary.md` 里自动生成「失败时间分布（窗口内均分 5 段）」：

| 失败分布形态 | 指向 |
|-------------|------|
| 全部集中在开跑后第一段（≈200ms 内） | 冷启动：连接池/JIT 未热 |
| 全程均匀散布 | 资源上限：accept 队列 / 文件描述符 |
| 只在中间一段（如 1 秒） | 服务瞬时不可用：重启或长 GC |

要复现就按这个矩阵跑（每组记：失败数、失败时间戳分布、`netstat -s` 里 listen 溢出计数、`docker stats` 峰值）：

| 组 | 做法 | 若复现说明 |
|----|------|-----------|
| A 冷启动 | 重启 seckill，就绪后 5 秒内跑 500 并发 ×3 | 冷启动问题 |
| B 热机对照 | 先 100 并发预热 30s，再跑 500 并发 ×3 | A 有 B 没有 → 确认冷启动 |
| C 调参 | `server.tomcat.accept-count` 100→1000 再跑 A | 消失 → accept 队列 |
| D 客户端对照 | 换 wrk/k6 跑同样 500 并发 | 不复现 → 问题在 Python 压测器 |

判定：A 复现 B 不复现 → 冷启动；C 后消失 → accept 队列；D 不复现 → 客户端；
四组都不复现 → 封存，不再追。

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

## 6. 结果记录

本轮实测数据已落到 `README.md` 的「压测数据」表，原始报告在 `tools/loadtest/results/<时间戳>-<场景>/summary.json`。

再跑新场景时，`summary.md` 末尾会自动生成这样的行，直接抄进 README：

```markdown
| 场景 | 并发 | QPS | P99 RT | 错误率 | 备注 |
|------|------|-----|--------|--------|------|
| 秒杀下单（MQ 削峰） | 200 | 89.0 | 3579 ms | 0% | 5000 用户 / 5000 单落库 / 0 超卖 |
```

填表前再确认一次：这行数字对应的 `summary.json` 里 `4003 = 0`、`oversold = false`。

---

## 7. 压测发现的问题 & 已知瓶颈（读源码得出，压测时逐条验证）

### 7.1 已修复：MyBatis `StdOutImpl` 打印 SQL，`System.out` 全局锁把线程串行化（P0，提升 5 倍）

**现象**：Hikari 从 10 调到 80，TPS 一直卡在 79–89，P99 从 6.8s 只降到 3.2s，怎么调都没用。

**定位**：对 seckill 服务连续打 4 次线程 dump（`jcmd Thread.print`），取一张快照，201 个 Tomcat 线程分布：

| 状态 | 线程数 | 栈顶 |
|------|--------|------|
| 等数据库连接 | 128 | `HikariPool.getConnection` |
| 等 `System.out` 锁 | 70 | `PrintStream.writeln` ← `MyBatis StdOutImpl.debug` |
| 真正干活 | 3 | — |

同时 MySQL `Slow_queries=0`、`PROCESSLIST` 里连接基本空闲 —— 数据库没问题，线程全在排队打 SQL 日志。

**根因**：5 个服务的 `application.yml` 都写了 `log-impl: org.apache.ibatis.logging.stdout.StdOutImpl`，
每条 SQL 走 `System.out.println`，而 `PrintStream` 是全局同步锁，Tomcat 线程池（默认 200）被整体串行化。

**修复**：`log-impl` 改为 `org.apache.ibatis.logging.slf4j.Slf4jImpl`（SQL 日志交给日志框架，按级别控制）。

**效果**：同一场景 **88 → 447 TPS**（复测 602 TPS / P99 669ms），错误率 0、5000 单落库、0 超卖。

### 7.2 已修复：商品详情缓存从未命中（P0，2026-10-22 实测发现）

**现象**：冷缓存 102.7 QPS / 热缓存 99.1 QPS，几乎没差；8 线程也只有 104 QPS。

**定位**：压 10s / 1051 请求，Redis `INFO commandstats` 增量：`EVAL`（Redisson 加锁脚本）+2520、`SET` +1252、`GET` +3756
→ **每请求 2.4 次加锁**，说明全部走的是「缓存未命中 → 分布式锁 → 查库」路径。

**根因**：`common/RedisConfig` 用 `Jackson2JsonRedisSerializer<Object>`（不带类型信息），
`ProductCacheService.getSpuCache` 读出的是 `String`/`LinkedHashMap`，`instanceof SpuVO` 永远 false，
于是删缓存 → 加锁 → 回源，缓存命中率恒为 0。

**修复**：`getSpuCache` 对 `String`/`Map` 用 Hutool 反序列化兜底；顺手确认 `setSpuCache` 写入格式一致。

**效果**：同 8 线程 104 → 449 QPS；64 线程热缓存 2385 QPS、P99 44ms（修复前 99 QPS / P99 1139ms）。

### 7.3 秒杀链路的瓶颈（实测数据支持）

| # | 瓶颈 | 代码位置 | 验证方法 | 可能的优化 |
|---|------|---------|---------|-----------|
| 1 | 每请求 3 次 DB 查询（活动、秒杀商品、`count(*)`） | `SeckillService.seckill` 步骤 1/2/4 | MySQL Questions 增速 ≈ 请求数 × 3 | 活动+商品配置进 Caffeine 本地缓存；`count(*)` 改成 Redis 计数 |
| 2 | `syncSend` 在请求线程里同步等 MQ 回执 | `SeckillService` 步骤 7 | 线程 dump 里能看到线程停在 `RocketMQTemplate.syncSend` | 改 `asyncSend` + 本地失败重试表 / 事务消息 |
| 3 | Redisson `tryLock` 每请求两次往返 | 步骤 3 | 关掉后 440 TPS vs 开启 447/602，**无差异** | 不必改；限购由 Lua 保证 |
| 4 | 单实例网关 + Tomcat 默认 200 线程 | gateway | 并发 > 200 后 P99 陡增 | 加实例 / 调 `server.tomcat.threads.max` |
| 5 | 过滤器链 + `JwtAuthFilter` 开销 | `JwtAuthFilter` | 对比直连 8084 与走 8080 的 RT 差 | 网关统一鉴权 |

**实测结论**：真正的瓶颈是第 0 条（日志），不是连接池、不是锁、也不是 DB 查询次数。

- Hikari 10→30→80：79 / 89 / 88 TPS，**无效**；
- 关掉 Redisson 锁：440 TPS，**无提升**（噪声内）；
- 关掉 StdOutImpl：447 TPS，复测 602 TPS，**唯一有效且成本最低的改动**；
- 加配置本地缓存（热点路径 DB 查询 3→0）：577 / 635 TPS，**仍在同一水平** —— 说明 200 并发下 DB 查询次数已不是瓶颈。

下一层要查的是 MQ 消费端（5000 条消息并发 Feign 扣库存，和 API 线程抢 CPU/连接）、
单实例网关与压测客户端自身的排队。这三项需要独立压测机或更细的埋点才能干净地区分。

### 7.4 已修复：扣库存与建单之间的一致性缺口（本地消息表）

原实现里「Redis 扣减 → `syncSend`」在同一个请求里，消息发不出去时库存已经扣了、用户还收到「排队中」。
真实 SKU 库存耗尽的那轮压测就复现了：5000 消耗 vs 4998 成功，2 单既无订单也不回补。
（**这是 Outbox 之前的旧版本现象**，本节下面的改法已经把这条路径补上；§4 表格里不含这一轮。）

**改法（Transactional Outbox）**：
1. 请求线程只做 Redis 扣减 + 写 `t_seckill_outbox`（`status=0`），立即返回「排队中」；
2. `SeckillOutboxPublisher` 每 200ms 捞 200 条 PENDING 投递 MQ，成功置 `status=1`；
3. 投递失败 `retry_count+1`，超过 10 次置 `status=2`，并回补 Redis 库存 + 清除限购/已购标记；
4. 写 outbox 本身失败 → 立即回补 Redis（`compensateRedis`），用户可重试。

**实测**：449 / 677 TPS，与同步 `syncSend`（440–635）同一区间 —— 省下的 MQ 回执被多出的一次 DB insert 抵消；
但两轮 outbox 5000 条全部投递成功、0 pending、0 failed，订单 5000 条，
**请求线程不再依赖 MQ 可用性，一致性缺口有了兜底**。


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

### 8.1 自研脚本 vs JMeter 对照（2026-10-23 实测）

**目的**：验证 `tools/loadtest/seckill_bench.py` 报出来的数字是否可信。

**条件**：同一台机器、同一套 4 容器 + 4 服务；1000 个独立用户（1000 个 token，一人一单）；
库存 1000；每轮前重置订单/outbox/Redis 并调用 `warm-up` 预热；JMeter 用 200 线程 × 5 循环，
自研脚本用 1000 用户 / 200 并发，等价。

| 轮次 | 工具 | 样本 | TPS | Avg | P99 | 实时窗口 |
|------|------|------|-----|-----|-----|---------|
| 1 | JMeter 5.6.3 | 1000 | 583.8 | 323 ms | 592 ms | 1.71 s |
| 1 | 自研脚本 | 1000 | 600.5 | 312 ms | 409 ms | 1.67 s |
| 2 | JMeter | 1000 | 526.3 | 363 ms | 627 ms | 1.90 s |
| 2 | 自研脚本 | 1000 | 714.8 | 269 ms | 470 ms | 1.40 s |
| 3 | JMeter | 1000 | 764.5 | 247 ms | 406 ms | 1.31 s |
| 3 | 自研脚本 | 1000 | 922.6 | 206 ms | 275 ms | 1.08 s |

**结论**：

- 两种工具有效量级一致（中位数 JMeter ~584，自研 ~715），差距 10~20%，
  在轮次间抖动（服务端 JIT/GC/连接池预热）范围内，**自研脚本的数字可信**；
- 都是 0 错误，服务端最终一致：订单 1000 条、outbox 1000 条全部投递、0 残留。

**两个必须知道的坑**：

1. **工具自身开销能吃掉一半吞吐**：最初那版 JMeter 带 Groovy 断言（JSR223）+ `subresults=true`，
   同场景只有 **282 TPS**（1000 样本 / 3.54 s），去掉断言和子结果写入后回到 584。
   说明同机压测时，**压测器的开销会直接压低被测指标**——这也解释了为什么两台工具的数字都低于服务端真实能力；
2. **JTL 会重复计数**：`subresults=true` 时断言结果会额外写一行，2000 行里只有 1000 个真实样本，
   必须按 `(timestamp, threadName, elapsed)` 去重，否则 TPS 会被算错一倍。

> 复现：JMeter 计划与 token CSV 在 `tools/loadtest/jmeter/`（见该目录说明），
> 跑前必须先调 `POST /api/seckill/warm-up/1`，JMeter 不会自动预热，否则全是 `4004`。

---

## 9. 面试口径

- **真的跑过**：报 `并发 / TPS / P99 / 错误率`，并主动说出「同一台机器上压的，绝对值不代表生产，看的是缓存前后、拐点位置」；
- **没跑成**：只说设计（「每个用户一个 token、库存要 ≥ 并发、要校验超卖」），
  不报数字。能讲清「为什么不能拿同一个 token 压 5000 并发」本身就是加分项。
