package com.ecommerce.payment.service;

import com.ecommerce.payment.feign.OrderFeignClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * MQ 消费者 — 监听支付成功消息，补偿更新订单状态
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "payment-success-topic",
        consumerGroup = "payment-success-consumer-group"
)
public class PaymentSuccessConsumer implements RocketMQListener<String> {

    private final OrderFeignClient orderFeignClient;

    @Override
    public void onMessage(String orderNo) {
        log.info("收到支付成功消息: orderNo={}", orderNo);
        try {
            com.ecommerce.common.Result<Void> result = orderFeignClient.payByOrderNo(orderNo);
            if (result != null && result.getCode() == 200) {
                log.info("MQ补偿：订单支付状态已更新: orderNo={}", orderNo);
            } else {
                log.error("MQ补偿：订单服务更新失败: orderNo={}", orderNo);
            }
        } catch (Exception e) {
            log.error("MQ补偿：调用订单服务异常: orderNo={}", orderNo, e);
        }
    }
}
