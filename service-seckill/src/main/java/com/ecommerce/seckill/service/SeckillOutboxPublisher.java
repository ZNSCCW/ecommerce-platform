package com.ecommerce.seckill.service;

import com.ecommerce.seckill.entity.SeckillOutbox;
import com.ecommerce.seckill.mapper.SeckillOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 本地消息表投递器。
 *
 * 秒杀请求只负责把消息写进 t_seckill_outbox（与 Redis 扣减同一个请求内完成），
 * 这里每 200ms 捞一批 PENDING 投递到 RocketMQ：
 *  - 投递成功 → status=1；
 *  - 投递失败 → retry_count+1，下一轮继续投；
 *  - 超过 max-retry → status=2，并回补 Redis 库存 + 清除该用户的限购/已购标记（幂等补偿）。
 *
 * 这样既把请求线程从「等 MQ 回执」里解放出来，也补上了原来
 * 「Redis 扣了库存但消息发不出去、既没订单也不回补」的一致性缺口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOutboxPublisher {

    private static final String TOPIC = "seckill-order-topic";
    private static final String STOCK_PREFIX = "seckill:stock:";
    private static final String ORDERED_PREFIX = "seckill:ordered:";
    private static final String LIMIT_PREFIX = "seckill:limit:";

    private final SeckillOutboxMapper outboxMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final StringRedisTemplate redisTemplate;

    @Value("${seckill.outbox.enabled:true}")
    private boolean enabled;

    @Value("${seckill.outbox.batch-size:200}")
    private int batchSize;

    @Value("${seckill.outbox.max-retry:10}")
    private int maxRetry;

    @Scheduled(fixedDelayString = "${seckill.outbox.delay-ms:200}")
    public void publishPending() {
        if (!enabled) {
            return;
        }
        List<SeckillOutbox> batch = outboxMapper.findPending(batchSize);
        if (batch.isEmpty()) {
            return;
        }
        int sent = 0;
        int failed = 0;
        for (SeckillOutbox msg : batch) {
            try {
                rocketMQTemplate.syncSend(TOPIC, MessageBuilder.withPayload(msg.getPayload()).build());
                msg.setStatus(SeckillOutbox.SENT);
                msg.setUpdatedAt(LocalDateTime.now());
                outboxMapper.updateById(msg);
                sent++;
            } catch (Exception e) {
                failed++;
                handleFailure(msg, e);
            }
        }
        log.info("[outbox] 本轮投递 {} 条, 成功 {}, 失败 {}", batch.size(), sent, failed);
    }

    private void handleFailure(SeckillOutbox msg, Exception e) {
        int retry = (msg.getRetryCount() == null ? 0 : msg.getRetryCount()) + 1;
        msg.setRetryCount(retry);
        msg.setUpdatedAt(LocalDateTime.now());
        if (retry >= maxRetry) {
            msg.setStatus(SeckillOutbox.FAILED);
            outboxMapper.updateById(msg);
            compensate(msg);
            log.error("[outbox] 投递失败已达上限, 已回补库存: id={}, user={}", msg.getId(), msg.getUserId(), e);
        } else {
            outboxMapper.updateById(msg);
            log.warn("[outbox] 投递失败, 第 {} 次重试: id={}", retry, msg.getId());
        }
    }

    /** 回补：Redis 库存 +1，并清掉该用户的已购/限购标记，允许他重新抢 */
    private void compensate(SeckillOutbox msg) {
        String suffix = msg.getActivityId() + ":" + msg.getSkuId() + ":" + msg.getUserId();
        redisTemplate.opsForValue().increment(STOCK_PREFIX + msg.getActivityId() + ":" + msg.getSkuId());
        redisTemplate.delete(ORDERED_PREFIX + suffix);
        redisTemplate.delete(LIMIT_PREFIX + suffix);
    }
}
