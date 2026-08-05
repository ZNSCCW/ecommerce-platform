package com.ecommerce.seckill.service;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import com.ecommerce.common.Result;
import com.ecommerce.seckill.entity.SeckillOrder;
import com.ecommerce.seckill.entity.SeckillProduct;
import com.ecommerce.seckill.feign.ProductFeignClient;
import com.ecommerce.seckill.mapper.SeckillOrderMapper;
import com.ecommerce.seckill.mapper.SeckillProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * RocketMQ 消费者 — 异步创建秒杀订单
 *
 * 消息格式: activityId:skuId:userId:seckillPrice
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "seckill-order-topic",
        consumerGroup = "seckill-order-consumer-group"
)
public class SeckillOrderConsumer implements RocketMQListener<String> {

    private final SeckillOrderMapper orderMapper;
    private final SeckillProductMapper productMapper;
    private final ProductFeignClient productFeignClient;

    @Override
    @Transactional
    public void onMessage(String message) {
        String[] parts = message.split(":");
        Long activityId = Long.parseLong(parts[0]);
        Long skuId = Long.parseLong(parts[1]);
        Long userId = Long.parseLong(parts[2]);
        BigDecimal seckillPrice = new BigDecimal(parts[3]);

        log.info("收到秒杀订单消息: activityId={}, skuId={}, userId={}", activityId, skuId, userId);

        try {
            // 1. 调用商品服务扣减真实库存
            Result<Boolean> result = productFeignClient.deductStock(skuId, 1);
            if (result == null || !Boolean.TRUE.equals(result.getData())) {
                log.error("秒杀订单扣减真实库存失败: skuId={}", skuId);
                // 抛出异常让 MQ 重试，避免 Redis 库存泄漏
                throw new RuntimeException("扣减真实库存失败: skuId=" + skuId);
            }

            // 2. 创建秒杀订单
            Snowflake snowflake = IdUtil.getSnowflake(1, 2);
            SeckillOrder order = new SeckillOrder();
            order.setOrderNo(String.valueOf(snowflake.nextId()));
            order.setUserId(userId);
            order.setActivityId(activityId);
            order.setSkuId(skuId);
            order.setSeckillPrice(seckillPrice);
            order.setQuantity(1);
            order.setStatus(0); // 待支付
            orderMapper.insert(order);

            log.info("秒杀订单创建成功: orderNo={}, userId={}", order.getOrderNo(), userId);
        } catch (Exception e) {
            log.error("秒杀订单创建失败: message={}", message, e);
            // 消费失败，MQ 会重试投递
            throw new RuntimeException("秒杀订单创建失败", e);
        }
    }
}
