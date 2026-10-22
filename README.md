# 微服务架构电商平台

> 从 0 到 1 搭建的微服务电商平台，包含用户、商品、订单、秒杀、支付五大核心服务。  
> 简历项目，技术栈覆盖 Spring Cloud Alibaba 全栈生态。

---

## 目录

- [架构图](#架构图)
- [技术栈](#技术栈)
- [模块说明](#模块说明)
- [快速启动](#快速启动)
- [接口概览](#接口概览)
- [项目亮点](#项目亮点)
- [实测验证](#实测验证)
- [踩坑与修复记录](#踩坑与修复记录)
- [压测数据](#压测数据)

---

## 架构图

```mermaid
graph TB
    Client[用户/前端] --> Gateway[Gateway 网关 :8080]
    
    subgraph 注册中心/配置中心
        Nacos[Nacos Server :8848]
    end
    
    subgraph 中间件
        MySQL[(MySQL :3306)]
        Redis[(Redis :6379)]
        ES[(Elasticsearch :9200)]
        RMQ[RocketMQ :9876]
    end
    
    Gateway --> User[用户服务 :8081]
    Gateway --> Product[商品服务 :8082]
    Gateway --> Order[订单服务 :8083]
    Gateway --> Seckill[秒杀服务 :8084]
    Gateway --> Payment[支付服务 :8085]
    
    User --> MySQL
    User --> Redis
    
    Product --> MySQL
    Product --> Redis
    Product --> ES
    
    Order --> MySQL
    Order --> RMQ
    Order --> Product
    
    Seckill --> MySQL
    Seckill --> Redis
    Seckill --> RMQ
    Seckill --> Product
    
    Payment --> MySQL
    Payment --> RMQ
    Payment --> Order
    
    Admin[Spring Boot Admin :8090] --> User
    Admin --> Product
    Admin --> Order
    Admin --> Seckill
    Admin --> Payment
    
    Nacos -.-> User
    Nacos -.-> Product
    Nacos -.-> Order
    Nacos -.-> Seckill
    Nacos -.-> Payment
    Nacos -.-> Gateway
    Nacos -.-> Admin
```

---

## 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 框架 | Spring Boot | 3.2.5 |
| JDK | Oracle JDK | 17+ |
| 微服务 | Spring Cloud Alibaba | 2023.0.1 |
| 注册/配置中心 | Nacos | 2.3.0 |
| 网关 | Spring Cloud Gateway | — |
| ORM | MyBatis-Plus | 3.5.5 |
| 数据库 | MySQL | 8.0 |
| 缓存 | Redis | 7.0 |
| 搜索 | Elasticsearch | 7.17 |
| 消息队列 | RocketMQ | 5.1.4 |
| 分布式锁 | Redisson | 3.29 |
| 服务容错 | Sentinel | — |
| 监控 | Spring Boot Admin | 3.2.3 |
| 工具集 | Hutool | 5.8 |
| JWT | JJWT | 0.12 |

---

## 模块说明

| 模块 | 端口 | 说明 |
|------|------|------|
| `gateway` | 8080 | 统一网关，路由转发、跨域、鉴权 |
| `common` | — | 公共模块：响应体、异常、JWT 工具、配置 |
| `ecommerce-admin` | 8090 | Spring Boot Admin 监控面板 |
| `service-user` | 8081 | 用户服务：注册/登录/JWT/RBAC 权限 |
| `service-product` | 8082 | 商品服务：CRUD/ES搜索/Redis缓存 |
| `service-order` | 8083 | 订单服务：状态机/Feign/延迟取消 |
| `service-seckill` | 8084 | 秒杀服务：Redis+Lua/MQ削峰/限流 |
| `service-payment` | 8085 | 支付服务：沙箱对接/回调幂等 |

---

## 快速启动

### 前置条件

- JDK 17+
- Maven 3.9+
- Docker Desktop（用于运行中间件）
- Navicat（管理 MySQL，可选）

### 1. 启动中间件

```bash
# 启动所有中间件（第一次会拉取镜像，较慢）
docker compose -f ecommerce-platform/docker-compose.yml up -d

# 确认全部 running
docker compose ps
```

> ⚠️ **本机环境注意事项**（踩坑总结，见文末[踩坑与修复记录](#踩坑与修复记录)）：
> 1. **本机已装 MySQL 占 3306 端口**时，docker 里的 `mysql` 容器会起不来——本项目服务写死连 `localhost:3306`，**直接用本机 MySQL 即可**（docker 只跑 redis/nacos/rocketmq/es/kibana）。以后启动中间件用：
>    ```bash
>    docker compose up -d redis nacos rmq-namesrv rmq-broker rmq-dashboard elasticsearch kibana
>    ```
> 2. **RocketMQ broker 首次启动报 `NullPointerException`（假象）**：根因是 Docker Desktop 的 named volume 挂载点属主为 root、broker 以 rocketmq 用户运行无写权限。重建 volume 后需执行一次：
>    ```bash
>    docker run --rm -v ecommerce-platform_broker-data://home/rocketmq/store --user root apache/rocketmq:5.1.4 chown -R rocketmq:rocketmq //home/rocketmq/store
>    ```
> 3. **不要重复启动多套服务**：`start-all.bat` 和手动 `java -jar` 会各自拉起一套进程，秒杀服务的 Redisson 锁跨实例互斥会导致随机"请勿重复下单"。

### 2. 初始化数据库

用 Navicat 连接 `localhost:3306`（root / 123456），运行：

```
文件 → 运行SQL文件 → 选择 docs/init.sql → 执行
```

### 3. 启动服务

**方式一：一键启动（推荐）**

```bash
cd ecommerce-platform
start-all.bat
```

会自动编译 + 依次启动所有服务，按任意键关闭全部。

**方式二：逐个启动**

按顺序启动（Nacos 就绪后）：

```bash
# 编译全部模块
mvn -f ecommerce-platform/pom.xml clean package -DskipTests

# 启动各个服务（每个开一个终端，或使用 IDE 启动类）
java -jar ecommerce-platform/ecommerce-admin/target/ecommerce-admin-1.0.0.jar
java -jar ecommerce-platform/gateway/target/gateway-1.0.0.jar
java -jar ecommerce-platform/service-user/target/service-user-1.0.0.jar
java -jar ecommerce-platform/service-product/target/service-product-1.0.0.jar
java -jar ecommerce-platform/service-order/target/service-order-1.0.0.jar
java -jar ecommerce-platform/service-seckill/target/service-seckill-1.0.0.jar
java -jar ecommerce-platform/service-payment/target/service-payment-1.0.0.jar
```

### 4. 验证

| 地址 | 说明 |
|------|------|
| `http://localhost:8080` | 网关 |
| `http://localhost:8848/nacos` | Nacos 控制台（服务注册列表） |
| `http://localhost:8090` | Admin 监控面板（admin/admin123） |
| `http://localhost:8088` | RocketMQ Dashboard |
| `http://localhost:5601` | Kibana |

---

## 接口概览

所有请求通过 Gateway（`:8080`）转发，Prefix 统一为 `/api`。

### 用户服务

| 方法 | 路径 | 说明 | 需登录 |
|------|------|------|--------|
| POST | `/api/user/register` | 注册 | ❌ |
| POST | `/api/user/login` | 登录 | ❌ |
| POST | `/api/user/refresh` | 刷新 Token | ❌ |
| POST | `/api/user/logout` | 登出 | ✅ |
| GET | `/api/user/info` | 当前用户信息 | ✅ |

### 商品服务

| 方法 | 路径 | 说明 | 需登录 |
|------|------|------|--------|
| GET | `/api/product/spu/page` | 商品分页 | ❌ |
| GET | `/api/product/spu/{id}` | 商品详情 | ❌ |
| POST | `/api/product/search` | ES 搜索商品 | ❌ |
| POST | `/api/product/spu` | 创建商品 | ✅ |
| PUT | `/api/product/spu` | 更新商品 | ✅ |
| PUT | `/api/product/spu/{id}/status` | 上下架 | ✅ |

### 订单服务

| 方法 | 路径 | 说明 | 需登录 |
|------|------|------|--------|
| POST | `/api/order` | 创建订单 | ✅ |
| GET | `/api/order/{id}` | 订单详情 | ✅ |
| GET | `/api/order/page` | 我的订单列表 | ✅ |
| POST | `/api/order/{id}/cancel` | 取消订单 | ✅ |

### 秒杀服务

| 方法 | 路径 | 说明 | 需登录 |
|------|------|------|--------|
| POST | `/api/seckill` | 秒杀下单 | ✅ |
| GET | `/api/seckill/activities` | 秒杀活动列表 | ❌ |
| GET | `/api/seckill/stock/{activityId}/{skuId}` | 实时库存 | ❌ |
| POST | `/api/seckill/warm-up/{activityId}` | 预热库存 | ✅ |

### 支付服务

| 方法 | 路径 | 说明 | 需登录 |
|------|------|------|--------|
| POST | `/api/payment` | 创建支付 | ✅ |
| POST | `/api/payment/notify` | 支付回调 | ❌（支付宝回调） |

---

## 项目亮点

### 秒杀系统（核心亮点）

```
请求 → Gateway(Sentinel限流) → 验证活动 → Lua原子扣库存
     → Redisson防重复 → MQ异步削峰 → 异步创建订单
```

- **Redis + Lua 脚本** 实现库存扣减原子性，消除超卖
- **RocketMQ 异步削峰** 将瞬时高并发流量打散为平滑消费
- **Redisson 分布式锁** 防止同一用户重复下单
- **Sentinel 限流**（依赖与配置已接入，流控规则待部署 dashboard 后启用）

### Redis 缓存三防

- **击穿**：Redisson 分布式锁 + 双重检测
- **穿透**：空值缓存（短 TTL 60s）
- **雪崩**：随机过期时间（基础 TTL + 0~300s 随机偏移）

### 订单状态机

基于 Map 驱动的状态机，严格校验状态转换合法性：

```
待支付 ──→ 已支付 ──→ 已发货 ──→ 已完成
  │
  └──→ 已取消（用户主动 / 30分钟超时自动）
```

---

## 实测验证

> 2026-08-13 全链路从零到一实机跑通（本机 MySQL + Docker 中间件），逐环节验证通过：

| # | 环节 | 结果 |
|---|------|------|
| 1 | 用户登录（普通用户 + 管理员） | ✅ JWT 签发/解析正常 |
| 2 | 商品浏览 | ✅ 分页/详情正常 |
| 3 | 下单 | ✅ 订单创建，Feign 取真实价格 |
| 4 | 取消订单（待支付） | ✅ 状态机流转 + Feign 恢复库存 |
| 5 | 支付（createPay + 回调验签） | ✅ HMAC 验签通过，订单 → 已支付 |
| 6 | 秒杀预热（管理员） | ✅ ADMIN 权限校验 |
| 7 | 秒杀 | ✅ Lua 扣库存 → MQ 削峰 → 异步建单 |

完整测试流程见 `docs/testing.md`。

---

## 踩坑与修复记录

> 开发/联调中发现并已修复的关键问题，作为复盘与面试素材。全部已提交并推送。

### 1. JwtAuthFilter 未填充 SecurityContext → 业务接口全 401（P0）

**现象**：登录、浏览正常，但下单/秒杀/支付等需登录接口一律返回 401。

**根因**：除 `service-user` 外，其余 4 个服务的 `JwtAuthFilter` 只 `request.setAttribute("userId")`，没有把身份写入 `SecurityContextHolder`。而各服务 `SecurityConfig` 都配了 `anyRequest().authenticated()`，Spring Security 的授权决策读的是 SecurityContext，匿名身份被 `authenticated()` 拒绝。

**修复**：过滤器解析 Token 后构造 `UsernamePasswordAuthenticationToken` 塞进 `SecurityContextHolder`（seckill 额外映射 `ROLE_ADMIN`）。

### 2. SecurityConfig 非法路径模式 `/**/pay` → PatternParseException（P0）

**现象**：order 服务任何请求都返回 401 "Token无效"（异常被 JwtAuthFilter 静默 catch 吞掉，伪装成 Token 问题）。

**根因**：`requestMatchers("/api/order/**/pay")` 里 `/**` 后面还跟 `/pay`——Spring 6 的 `PathPatternParser`（MvcRequestMatcher 底层）**不允许 `**` 后跟其他模式元素**，解析时抛 `PatternParseException`。

**修复**：改为 `/api/order/*/pay`（单段通配）。同时给 JwtAuthFilter 的 catch 补上 `log.error`（原静默吞异常导致排查困难）。

### 3. 秒杀 lockKey 与 orderedKey 同 key → 永远"请勿重复下单"（P0）

**现象**：秒杀永远返回 4003 请勿重复下单，从未成功。

**根因**：`SeckillService` 里分布式锁 key 和"已下单"去重 key 都用 `ORDERED_PREFIX + activityId:skuId:userId` 拼接——**tryLock 刚创建的锁，紧接着 `hasKey` 检查就命中，100% 误判为已下单**。

**修复**：新增独立 `LOCK_PREFIX = "seckill:lock:"`，锁 key 与去重 key 分离。定位手段：Redis `MONITOR` 抓命令 + 打日志确认 `REPEAT@orderedKey` 分支。

### 4. Redisson codec 序列化 Lua 参数 → "compare number with nil"（P0）

**现象**：秒杀 Lua 脚本报 `ERR attempt to compare number with nil`。

**根因**：seckill 的 `RedisTemplate` 实际是 `RedissonConnectionFactory`（Redisson 自动配置替换），Redisson 默认 codec 会把 `ARGV["1"]` 等参数序列化，Lua 里 `tonumber(ARGV[1])` 拿到 nil。手动 EVAL 明文参数可成功，对照实验定位。

**修复**：改用 `StringRedisTemplate`（String 序列化，key/value/args 均为明文，与 Lua `tonumber` 兼容）。

### 5. 环境类坑（不影响代码，但启动必踩）

- **3306 端口冲突**：本机 MySQL 占 3306，docker mysql 容器起不来；服务写死 localhost:3306，直接用本机 MySQL。
- **RocketMQ broker 假 NPE**：Docker Desktop named volume 属主为 root，broker 无写权限 → store 初始化失败，异常被 shutdown 的 NPE 掩盖。
- **双实例**：多套服务进程并存时 Redisson 锁跨实例互斥，秒杀随机判重。
- **localhost 解析**：Java 解析 `localhost` 可能走 IPv6 `::1`（wslrelay），排查 Redis 时需注意与 `127.0.0.1`（docker）区分。

---

## 压测数据

> 工具：`tools/loadtest/seckill_bench.py`（零依赖 Python，每请求独立用户、自校验超卖）。
> 环境：单机 ASUS TUF FA507NV / Win11 / 16 核 16G / Docker VM 3G / 压测器与被测服务同机。
> 绝对值受同机抢 CPU 与 Docker 资源上限影响，**看相对提升与拐点，不看绝对值**。

| 场景 | 并发 | QPS | Avg RT | P99 RT | 错误率 | 备注 |
|------|------|-----|--------|--------|--------|------|
| 商品详情 · 缓存失效（修复前） | 64 | 99 | 642 ms | 1139 ms | 0% | 每请求都走 Redisson 锁 + DB，见下方复盘 |
| 商品详情 · 冷缓存（修复后） | 64 | 1618 | 39.5 ms | 576 ms | 0% | 1000 条商品随机 id，首访回源 |
| 商品详情 · 热缓存（修复后） | 64 | 2385 | 26.8 ms | 44 ms | 0% | 缓存命中，P99 较修复前降 25 倍 |
| 秒杀下单 · 300 并发（基线） | 300 | 79 | 3718 ms | 6837 ms | 0% | 5000 用户 / 库存 5000 / 5000 单落库 / 0 超卖 |
| 秒杀下单 · 200 并发（调大 Hikari 10→80） | 200 | 88 | 2252 ms | 3222 ms | 0% | 连接池不是瓶颈，吞吐仍 ~90 |
| 秒杀下单 · 200 并发（关闭 StdOutImpl 后） | 200 | 447 | 443 ms | 2632 ms | 0% | 同一台机、同一份数据，**5 倍提升** |
| 秒杀下单 · 200 并发（复测） | 200 | 602 | 331 ms | 669 ms | 0% | 0 错误、0 重复、5000 单落库 |
| 秒杀下单 · 200 并发（再关 Redisson 锁） | 200 | 440 | 450 ms | 2481 ms | 0% | 无提升，锁不是瓶颈，保持默认开启 |
| 秒杀下单 · 200 并发（+ 配置本地缓存） | 200 | 577 | 339 ms | 589 ms | 0% | 热点路径 DB 查询 3→0，吞吐无变化 |
| 秒杀下单 · 200 并发（配置缓存复测） | 200 | 635 | 312 ms | 478 ms | 0% | 同上，波动范围内 |
| 秒杀下单 · 200 并发（配置缓存 + 关锁） | 200 | 545 | 362 ms | 2074 ms | 0% | 锁仍不是瓶颈 |
| 秒杀下单 · 200 并发（Outbox 异步投递） | 200 | 449 | 441 ms | 1774 ms | 0% | 5000 单落库、outbox 0 残留 |
| 秒杀下单 · 200 并发（Outbox 复测） | 200 | 677 | 291 ms | 458 ms | 0% | 同上，本轮最佳 |
| 全链路混合 | — | 未测 | — | — | — | 依赖 ES，本轮未起 |

### 压测发现并修复的问题

**1. MyBatis 用 `StdOutImpl` 打印 SQL，`System.out` 全局锁把线程串行化（P0，提升 5 倍）**

`application.yml` 里 `log-impl: org.apache.ibatis.logging.stdout.StdOutImpl`，每条 SQL 都 `System.out.println`；
线程 dump 里 200 个 Tomcat 线程有 128 个停在 `PrintStream.writeln`（MyBatis `StdOutImpl.debug`），
剩下 72 个在等 Hikari 连接 —— 数据库其实是空的（`Slow_queries=0`），线程全在排队打日志。

- 调大 Hikari 10→30→80 完全无效（79 / 89 / 88 TPS），因为序列化点是 stdout 不是 DB；
- 关掉 StdOutImpl 后同一场景 **88 → 447 TPS**，复测 602 TPS / P99 669ms；
- 修复：5 个服务的 `log-impl` 改为 `Slf4jImpl`（SQL 日志交给日志框架，按级别控制）。

**2. 商品详情缓存从未命中（P0）**

`RedisConfig` 用 `Jackson2JsonRedisSerializer<Object>`，写入是 JSON、读出是 `String`/`LinkedHashMap`，
`ProductCacheService.getSpuCache` 里 `instanceof SpuVO` 永远不成立，于是每个请求都「判定脏数据 → 删缓存 → 加分布式锁 → 查库」。

- 证据：压 10s/1051 请求，Redis `EVAL`（Redisson 锁）增加 2520 次、`SET` 增加 1252 次，即**每请求 2.4 次加锁脚本**；
- 修复：`getSpuCache` 对 `String` / `Map` 做反序列化兜底；
- 效果：同 8 线程 QPS 104 → 449，64 线程热缓存 2385 QPS、P99 44ms。

**3. 扣库存与建单之间没有补偿（一致性缺口，未修）**

一轮压测里真实 SKU 库存被前几轮耗尽，消费端 `deductStock` 失败 → 消息重试 16 次进死信；
但 Redis 库存已经扣掉、用户已收到「排队中」，最终既没订单也不退库存。

- 这不是 Lua 的问题，而是「Redis 扣减 → MQ 发送 → 消费建单」之间缺最终一致兜底；
- 建议：本地消息表 / RocketMQ 事务消息，消费失败回滚 Redis 库存或落补偿表；或秒杀活动前校验 Redis 库存 ≤ 真实 SKU 库存。

**4. Redisson 锁：压测证明可以不动**

关掉每用户分布式锁（`-Dseckill.lock.enabled=false`）后 440 TPS，与开启时 447/602 在噪声范围内；
限购本来就由 Lua 的 `KEYS[2]` 原子保证，所以这把锁只增加两次 Redis 往返，不带来收益 —— 但也不值得为它改代码，保持默认开启。

**5. 活动/商品配置本地缓存：DB 查询 3→0，吞吐没变（收益在数据库负载）**

针对「每请求 3 次 DB 查询」加了 3 秒 TTL 的进程内缓存（`SeckillConfigCache`），并把最后那次
`countByUserAndActivity` 从热点路径移除（防重改由 Redis orderedKey + Lua 限购 + DB 唯一索引兜底）。

- 实测：577 / 635 TPS，与不做缓存的 602 在同一水平；
- 说明 200 并发下瓶颈已经不在 DB 查询次数，而在 MQ 消费端的资源竞争与单实例网关；
- 缓存仍然保留：它把热点路径的数据库查询从 3 次降到 0 次，**降低 DB 压力**，只是在这个并发量级上还没成为吞吐瓶颈。

**6. 本地消息表（Outbox）异步投递：吞吐不变，但补上了一致性缺口**

秒杀请求不再同步等 MQ 回执，改为写 `t_seckill_outbox` 后立即返回，后台每 200ms 批量投递（`SeckillOutboxPublisher`）：
投递失败重试 10 次，仍失败则 `status=2` 并回补 Redis 库存 + 清除限购标记。

- 实测：449 / 677 TPS，与同步 `syncSend` 的 440~635 同一区间 —— 省下的 MQ 回执时间被「写 outbox 的那次 DB insert」抵消；
- 但换来了两件事：**请求线程不再依赖 MQ 可用性**，以及之前「扣了库存但没订单」的缺口有了补偿路径；
- 验证：两轮各 5000 条 outbox 全部 `status=1`、0 pending、0 failed，订单表 5000 条。

> 复现：`docs/benchmark.md` §4；原始报告在 `tools/loadtest/results/`（已 gitignore）。

---

## 项目结构

```
ecommerce-platform/
├── docker-compose.yml           # 中间件编排
├── pom.xml                      # Maven 父工程
├── docs/
│   ├── init.sql                 # 全量建库建表脚本
│   ├── testing.md               # 功能测试流程
│   └── benchmark.md             # 压测方案（含失效方案复盘）
├── tools/loadtest/              # 压测工具（零依赖 Python）
│   ├── seckill_bench.py         # 秒杀/商品/混合流量压测器
│   ├── README.md                # 执行手册
│   └── sql/                     # 压测数据准备（重置库存、灌 1000 条商品）
├── common/                      # 公共模块
├── gateway/                     # 网关
├── ecommerce-admin/             # Spring Boot Admin 监控
├── service-user/                # 用户服务
├── service-product/             # 商品服务
├── service-order/               # 订单服务
├── service-seckill/             # 秒杀服务
└── service-payment/             # 支付服务
```
