package com.ecommerce.order.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 消费者 — 监听支付超时消息，自动取消订单
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "order-cancel-topic",
        consumerGroup = "order-cancel-consumer-group"
)
public class OrderTimeoutConsumer implements RocketMQListener<String> {

    private final OrderService orderService;

    @Override
    public void onMessage(String orderIdStr) {
        Long orderId;
        try {
            orderId = Long.parseLong(orderIdStr);
        } catch (NumberFormatException e) {
            // 脏消息：非数字 orderId 直接丢弃并记录，避免异常消息拖垮消费端
            log.warn("收到非法超时消息，已丢弃: {}", orderIdStr);
            return;
        }
        log.info("收到超时取消消息: orderId={}", orderId);
        try {
            orderService.cancelExpired(orderId);
            log.info("超时订单已取消: orderId={}", orderId);
        } catch (Exception e) {
            log.error("超时取消订单失败: orderId={}", orderId, e);
        }
    }
}
