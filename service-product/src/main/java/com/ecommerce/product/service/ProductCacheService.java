package com.ecommerce.product.service;

import com.ecommerce.product.dto.SpuVO;
import com.ecommerce.product.entity.Spu;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 商品缓存服务 - Cache Aside + 防击穿/穿透/雪崩
 */
@Service
public class ProductCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;
    private final ProductService productService;

    public ProductCacheService(RedisTemplate<String, Object> redisTemplate,
                               RedissonClient redissonClient,
                               @Lazy ProductService productService) {
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.productService = productService;
    }

    private static final String CACHE_PREFIX = "product:spu:";
    private static final String LOCK_PREFIX = "lock:product:spu:";

    /** 缓存过期时间基数（秒），加随机偏移防雪崩 */
    private static final long CACHE_TTL_BASE = 3600; // 1小时

    /**
     * 从缓存获取 SPU 详情
     */
    public SpuVO getSpuCache(Long spuId) {
        String key = CACHE_PREFIX + spuId;
        Object val = redisTemplate.opsForValue().get(key);
        if (val != null) {
            // 空值标记（防穿透）
            if ("null".equals(val)) {
                return null;
            }
            // 类型安全转换，防止其他模块写入非 SpuVO 数据导致 ClassCastException
            if (val instanceof SpuVO) {
                return (SpuVO) val;
            }
            // 类型不匹配 → 清除脏数据
            redisTemplate.delete(key);
            return null;
        }
        return null;
    }

    /**
     * 设置缓存（含随机过期时间防雪崩）
     */
    public void setSpuCache(Long spuId, SpuVO spuVO) {
        String key = CACHE_PREFIX + spuId;
        // 基础 TTL + 随机 0~300 秒，防止大量缓存同时过期
        long ttl = CACHE_TTL_BASE + (long) (Math.random() * 300);
        redisTemplate.opsForValue().set(key, spuVO, ttl, TimeUnit.SECONDS);
    }

    /**
     * 双重检测 + 分布式锁，防缓存击穿
     * 调用方先 getSpuCache，未命中时调此方法
     */
    public SpuVO loadWithLock(Long spuId) {
        String lockKey = LOCK_PREFIX + spuId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            // 尝试加锁，最多等待 3 秒，锁 10 秒自动释放
            if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                try {
                    // 双重检测：拿到锁后再次检查缓存
                    SpuVO cached = getSpuCache(spuId);
                    if (cached != null) {
                        return cached;
                    }
                    // 查数据库构建缓存（已走缓存查询，不会无限递归）
                    Spu spu = productService.getSpuDetailInternal(spuId);
                    if (spu == null) {
                        setNullCache(spuId);
                        return null;
                    }
                    SpuVO vo = productService.buildSpuVO(spu);
                    setSpuCache(spuId, vo);
                    return vo;
                } finally {
                    lock.unlock();
                }
            } else {
                // 没拿到锁 → 自旋等待（最多 5 次，每次 100ms）
                for (int i = 0; i < 5; i++) {
                    Thread.sleep(100);
                    SpuVO cached = getSpuCache(spuId);
                    if (cached != null) {
                        return cached;
                    }
                }
                // 自旋超时后降级直接查 DB
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 清除缓存
     */
    public void evictSpuCache(Long spuId) {
        redisTemplate.delete(CACHE_PREFIX + spuId);
    }

    /**
     * 设置空值缓存防穿透（TTL 较短，60秒）
     */
    public void setNullCache(Long spuId) {
        String key = CACHE_PREFIX + spuId;
        redisTemplate.opsForValue().set(key, "null", 60, TimeUnit.SECONDS);
    }
}
