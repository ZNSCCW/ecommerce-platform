package com.ecommerce.seckill.service;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import com.ecommerce.common.BusinessException;
import com.ecommerce.common.ResultCode;
import com.ecommerce.seckill.dto.SeckillRequest;
import com.ecommerce.seckill.dto.SeckillResponse;
import com.ecommerce.seckill.entity.SeckillActivity;
import com.ecommerce.seckill.entity.SeckillOrder;
import com.ecommerce.seckill.entity.SeckillOutbox;
import com.ecommerce.seckill.entity.SeckillProduct;
import com.ecommerce.seckill.feign.ProductFeignClient;
import com.ecommerce.seckill.mapper.SeckillActivityMapper;
import com.ecommerce.seckill.mapper.SeckillOrderMapper;
import com.ecommerce.seckill.mapper.SeckillOutboxMapper;
import com.ecommerce.seckill.mapper.SeckillProductMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀核心服务
 *
 * 架构流程：
 * 请求 → Sentinel 限流 → 验证活动时间/用户限购
 *     → Redis Lua 原子扣库存 → Redisson 防重复 → MQ 异步下单
 *     → 返回"排队中"
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillService {

    /**
     * 用 StringRedisTemplate（String 序列化）：Lua 脚本与预热库存的 key/value 都必须是明文可被 tonumber 解析。
     * 若用 Redisson 默认 codec 的 RedisTemplate，ARGS 会被序列化导致 Lua tonumber 拿到 nil。
     */
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final RocketMQTemplate rocketMQTemplate;
    private final SeckillActivityMapper activityMapper;
    private final SeckillProductMapper productMapper;
    private final SeckillOrderMapper orderMapper;
    private final ProductFeignClient productFeignClient;
    private final com.ecommerce.seckill.config.SeckillConfigCache configCache;
    private final SeckillOutboxMapper outboxMapper;

    /** 是否走本地消息表（Outbox）异步投递；false 时退回原来的同步 syncSend */
    @Value("${seckill.outbox.enabled:true}")
    private boolean outboxEnabled;

    /** Lua 脚本缓存 */
    private DefaultRedisScript<Long> stockLuaScript;

    /** 库存缓存前缀 */
    private static final String STOCK_PREFIX = "seckill:stock:";
    /** 用户限购前缀 */
    private static final String LIMIT_PREFIX = "seckill:limit:";
    /** 已购用户去重前缀（订单去重标记） */
    private static final String ORDERED_PREFIX = "seckill:ordered:";
    /** 分布式锁前缀（必须与 ORDERED_PREFIX 不同！否则 tryLock 创建的锁会被 hasKey 误判为已下单） */
    private static final String LOCK_PREFIX = "seckill:lock:";

    /** 是否启用每用户分布式锁（一人一单已由 Lua 的限购 key 原子保证，可用 -Dseckill.lock.enabled=false 关闭做压测对比） */
    @Value("${seckill.lock.enabled:true}")
    private boolean lockEnabled;

    @PostConstruct
    public void init() {        // 加载 Lua 脚本
        stockLuaScript = new DefaultRedisScript<>();
        stockLuaScript.setLocation(new ClassPathResource("lua/seckill_stock.lua"));
        stockLuaScript.setResultType(Long.class);
    }

    // ==================== 库存预热 ====================

    /**
     * 活动开始前，将秒杀库存从 DB 加载到 Redis
     */
    public void warmUpStock(Long activityId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException(400, "活动不存在");
        }

        // 计算活动剩余时长（用于 Redis key 过期）
        long expireSec = java.time.Duration.between(LocalDateTime.now(), activity.getEndTime()).getSeconds();
        if (expireSec <= 0) expireSec = 3600; // 兜底 1 小时

        List<SeckillProduct> products = productMapper.findByActivityId(activityId);
        for (SeckillProduct p : products) {
            String stockKey = STOCK_PREFIX + activityId + ":" + p.getSkuId();
            redisTemplate.opsForValue().set(stockKey, String.valueOf(p.getSeckillStock()), expireSec, TimeUnit.SECONDS);
            log.info("秒杀库存预热: key={}, stock={}, ttl={}s", stockKey, p.getSeckillStock(), expireSec);
        }
        // 预热读完 DB 的最新配置，让进程内缓存立即失效，避免继续用旧的活动时间/价格
        configCache.invalidateAll();
    }

    // ==================== 秒杀核心 ====================

    /**
     * 秒杀请求入口
     */
    public SeckillResponse seckill(Long userId, SeckillRequest request) {
        Long activityId = request.getActivityId();
        Long skuId = request.getSkuId();

        // 1. 校验活动有效性（走进程内缓存，3 秒 TTL；warm-up 会主动失效）
        SeckillActivity activity = configCache.getActivity(activityId, activityMapper::selectById);
        if (activity == null || activity.getStatus() != 1) {
            throw new BusinessException(ResultCode.SECKILL_NOT_START);
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getStartTime())) {
            throw new BusinessException(ResultCode.SECKILL_NOT_START);
        }
        if (now.isAfter(activity.getEndTime())) {
            throw new BusinessException(ResultCode.SECKILL_ENDED);
        }

        // 2. 获取秒杀商品配置（走进程内缓存）
        SeckillProduct seckillProduct = configCache.getProduct(activityId, skuId, () ->
                productMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillProduct>()
                                .eq(SeckillProduct::getActivityId, activityId)
                                .eq(SeckillProduct::getSkuId, skuId)));
        if (seckillProduct == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }

        // 3. 分布式锁：防止同一个用户重复提交（key 粒度 = 用户 + 活动 + SKU）
        // 注意 lockKey 前缀必须与 orderedKey 不同，否则锁 key 会被下面的 hasKey 误判为已下单
        // 每请求一次 tryLock + 一次 unlock = 至少两次 Redis 往返，压测中是仅次于日志的瓶颈；
        // 一人一单其实已由 Lua 的 KEYS[2] 限购 key 原子保证，可用 seckill.lock.enabled=false 关掉做对比。
        String lockKey = LOCK_PREFIX + activityId + ":" + skuId + ":" + userId;
        RLock lock = null;
        if (lockEnabled) {
            lock = redissonClient.getLock(lockKey);
            try {
                if (!lock.tryLock(0, 5, TimeUnit.SECONDS)) {
                    // 没拿到锁说明正在处理，视为重复
                    log.warn("[seckill] REPEAT@tryLock: lockKey={}", lockKey);
                    throw new BusinessException(ResultCode.SECKILL_REPEAT);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[seckill] REPEAT@Interrupted: lockKey={}", lockKey);
                throw new BusinessException(ResultCode.SECKILL_REPEAT);
            }
        }

        try {
            // 4. 检查是否已下过单（Redis 标记 + DB 双检）
            String orderedKey = ORDERED_PREFIX + activityId + ":" + skuId + ":" + userId;
            if (Boolean.TRUE.equals(redisTemplate.hasKey(orderedKey))) {
                log.warn("[seckill] REPEAT@orderedKey: {}", orderedKey);
                throw new BusinessException(ResultCode.SECKILL_REPEAT);
            }
            // DB 双检：原本每请求一次 count(*)，是热点路径上第三次 DB 查询。
            // 压测后移到「防重链条」的最后一环：
            //   ① SeckillConfigCache 拿限购配置（本地缓存，不打库）
            //   ② orderedKey（Redis，30 分钟）
            //   ③ Lua 里的 KEYS[2] 限购计数（原子，活动时长）
            //   ④ 消费端建单时 uk_user_activity 唯一索引兜底
            // 前 ①②③ 已能挡住重复下单，④ 是 Redis 丢数据时的最后防线，
            // 因此热点路径不再查这条 count(*)。

            // 5. Lua 原子扣库存
            //   Lua 参数: KEYS[1]=库存key KEYS[2]=限购key ARGV[1]=扣减数量 ARGV[2]=每人限购 ARGV[3]=限购过期秒数
            String stockKey = STOCK_PREFIX + activityId + ":" + skuId;
            String limitKey = LIMIT_PREFIX + activityId + ":" + skuId + ":" + userId;
            long expireSec = java.time.Duration.between(now, activity.getEndTime()).getSeconds();

            Long result = redisTemplate.execute(stockLuaScript,
                    Arrays.asList(stockKey, limitKey),
                    "1",  // ARGV[1] 扣减数量
                    String.valueOf(seckillProduct.getLimitPerUser()),  // ARGV[2] 每人限购
                    String.valueOf(expireSec));  // ARGV[3] 过期时间

            if (result == null || result <= 0) {
                if (result != null && result == -1) {
                    log.warn("[seckill] REPEAT@luaLimit: limitKey={}, stockKey={}", limitKey, stockKey);
                    throw new BusinessException(ResultCode.SECKILL_REPEAT);
                }
                log.warn("[seckill] STOCK_EMPTY@lua: result={}, stockKey={}", result, stockKey);
                throw new BusinessException(ResultCode.SECKILL_STOCK_EMPTY);
            }

            // 6. 标记已下单（Redis，防止重复提交）
            redisTemplate.opsForValue().set(orderedKey, "1", 30, TimeUnit.MINUTES);

            // 7. 异步建单：写本地消息表（Outbox），由 SeckillOutboxPublisher 异步投递 MQ。
            //    请求线程不再等 MQ 回执；写库失败会回补 Redis 库存，投递失败由 publisher 重试/补偿。
            String msgBody = activityId + ":" + skuId + ":" + userId + ":" + seckillProduct.getSeckillPrice();
            if (outboxEnabled) {
                try {
                    SeckillOutbox outbox = new SeckillOutbox();
                    outbox.setActivityId(activityId);
                    outbox.setSkuId(skuId);
                    outbox.setUserId(userId);
                    outbox.setPayload(msgBody);
                    outbox.setStatus(SeckillOutbox.PENDING);
                    outbox.setRetryCount(0);
                    outbox.setCreatedAt(LocalDateTime.now());
                    outbox.setUpdatedAt(LocalDateTime.now());
                    outboxMapper.insert(outbox);
                } catch (Exception e) {
                    // 本地消息都没写进去：撤销 Redis 侧的扣减与标记，让用户还能重试
                    compensateRedis(stockKey, limitKey, orderedKey);
                    log.error("[seckill] 写本地消息表失败, 已回补库存: userId={}, activityId={}", userId, activityId, e);
                    throw new BusinessException(ResultCode.FAILED);
                }
            } else {
                rocketMQTemplate.syncSend("seckill-order-topic",
                        MessageBuilder.withPayload(msgBody).build());
            }

            // 返回排队成功
            return SeckillResponse.builder()
                    .code(SeckillResponse.QUEUED)
                    .msg("正在排队，请稍后查看订单结果")
                    .build();

        } finally {
            if (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * Redis 侧回滚：库存 +1，并清掉限购/已购标记，保证「扣了库存但没建单」不会把用户卡死。
     */
    private void compensateRedis(String stockKey, String limitKey, String orderedKey) {
        try {
            redisTemplate.opsForValue().increment(stockKey);
            redisTemplate.delete(limitKey);
            redisTemplate.delete(orderedKey);
        } catch (Exception e) {
            log.error("[seckill] Redis 回补失败，需人工核对: stockKey={}", stockKey, e);
        }
    }

    // ==================== 查询秒杀活动 ====================
    public List<SeckillActivity> getActiveActivities() {
        return activityMapper.findActiveActivities(LocalDateTime.now());
    }

    public List<SeckillProduct> getActivityProducts(Long activityId) {
        return productMapper.findByActivityId(activityId);
    }

    /**
     * 查询秒杀库存（Redis + DB 兜底）
     */
    public int getStock(Long activityId, Long skuId) {
        String key = STOCK_PREFIX + activityId + ":" + skuId;
        String val = redisTemplate.opsForValue().get(key);
        if (val != null) {
            try {
                return Integer.parseInt(val);
            } catch (NumberFormatException ignore) {
                // 值异常按无缓存处理，走 DB 兜底
            }
        }
        // Redis 无数据则查 DB
        SeckillProduct product = productMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillProduct>()
                        .eq(SeckillProduct::getActivityId, activityId)
                        .eq(SeckillProduct::getSkuId, skuId));
        return product != null ? product.getSeckillStock() : 0;
    }
}
