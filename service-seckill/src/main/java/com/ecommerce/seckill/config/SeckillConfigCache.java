package com.ecommerce.seckill.config;

import com.ecommerce.seckill.entity.SeckillActivity;
import com.ecommerce.seckill.entity.SeckillProduct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 秒杀活动 / 商品配置的进程内缓存。
 *
 * 背景：压测发现每个秒杀请求在扣 Redis 之前要查 2 次 DB（活动、秒杀商品），
 * 200 并发时这两次查询把数据库连接池和 Tomcat 线程都占满了。
 * 活动和商品配置在一次活动期间几乎不变，适合放进进程内缓存。
 *
 * 实现：ConcurrentHashMap + 固定 TTL（3 秒），不引入 Caffeine 依赖。
 * TTL 取 3 秒的取舍：
 *  - 秒杀开始/结束的边界误差最多 3 秒（可接受，库存预热本身也有延迟）；
 *  - 运营改价/改库存最多 3 秒生效，且 warm-up 会主动失效。
 * 多实例部署时各实例独立缓存，最长 3 秒不一致；要更强一致性可以换成
 * Redis pub/sub 广播失效或 Nacos 配置推送。
 */
@Slf4j
@Component
public class SeckillConfigCache {

    private static final long TTL_MS = 3_000L;

    private record Entry(Object value, long expireAt) {
        boolean alive(long now) {
            return now < expireAt;
        }
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    public SeckillActivity getActivity(Long activityId, Function<Long, SeckillActivity> loader) {
        return get("activity:" + activityId, SeckillActivity.class, () -> loader.apply(activityId));
    }

    public SeckillProduct getProduct(Long activityId, Long skuId, Supplier<SeckillProduct> loader) {
        return get("product:" + activityId + ":" + skuId, SeckillProduct.class, loader);
    }

    @SuppressWarnings("unchecked")
    private <T> T get(String key, Class<T> type, Supplier<T> loader) {
        long now = System.currentTimeMillis();
        Entry entry = store.get(key);
        if (entry != null && entry.alive(now)) {
            return (T) entry.value();
        }
        T loaded = loader.get();
        if (loaded == null) {
            // 空值也缓存 1 秒，避免活动不存在时被反复打库
            store.put(key, new Entry(null, now + 1_000L));
            return null;
        }
        store.put(key, new Entry(loaded, now + TTL_MS));
        return loaded;
    }

    /** 预热 / 后台改配置时调用，下一次请求立即回源 */
    public void invalidateAll() {
        int size = store.size();
        store.clear();
        if (size > 0) {
            log.info("[seckill] 配置本地缓存已失效, entries={}", size);
        }
    }
}
