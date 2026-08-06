package com.ecommerce.payment.service;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import com.ecommerce.common.BusinessException;
import com.ecommerce.payment.config.PayConfig;
import com.ecommerce.payment.dto.PayRequest;
import com.ecommerce.payment.dto.PayResponse;
import com.ecommerce.payment.entity.PayRecord;
import com.ecommerce.payment.feign.OrderFeignClient;
import com.ecommerce.payment.mapper.PayRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PayConfig payConfig;
    private final PayRecordMapper payRecordMapper;
    private final OrderFeignClient orderFeignClient;
    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 创建支付
     *
     * 当前为模拟模式（mockMode=true），直接返回模拟支付表单。
     * 集成支付宝时：
     * 1. 设置 pay.mock-mode=false
     * 2. 取消 pom.xml 中 alipay-sdk-java 依赖注释
     * 3. 在 application.yml 中填入真实的沙箱凭证
     * 4. 取消 PaymentService.createPay 中 AlipayTradePagePayRequest 相关代码注释
     */
    public PayResponse createPay(PayRequest request, Long userId) {
        if (!payConfig.isMockMode()) {
            throw new BusinessException(500, "未集成真实支付宝：pay.mock-mode=false 但 alipay-sdk 未启用。"
                    + "学习环境请设置 pay.mock-mode=true 使用模拟支付；"
                    + "正式集成请按 PayConfig 注释引入 alipay-sdk 并填沙箱凭证");
        }
        Snowflake snowflake = IdUtil.getSnowflake(1, 3);
        String payNo = String.valueOf(snowflake.nextId());

        // 创建支付记录（金额统一保留 2 位小数，避免精度歧义）
        java.math.BigDecimal amount = request.getAmount().setScale(2, java.math.RoundingMode.HALF_UP);
        PayRecord record = new PayRecord();
        record.setPayNo(payNo);
        record.setOrderNo(request.getOrderNo());
        record.setUserId(userId);
        record.setPayAmount(amount);
        record.setPayChannel(request.getChannel() != null ? request.getChannel() : 1);
        record.setStatus(0);
        payRecordMapper.insert(record);

        // 模拟支付表单（集成真实支付宝时替换为 AlipayTradePagePayRequest）
        String tradeNo = "MOCK" + System.currentTimeMillis();
        String totalAmount = amount.toPlainString();
        // 对回调参数做 HMAC 签名：handleNotify 验签通过才允许更新状态，防止伪造回调
        // 签名必须覆盖 trade_status，否则攻击者可篡改该字段而签名仍有效
        String sign = hmacSign(payNo, tradeNo, "TRADE_SUCCESS", totalAmount);

        String mockForm = "<form id='alipay-submit' action='" + htmlEscape(payConfig.getNotifyUrl())
                + "' method='POST'>"
                + "<input type='hidden' name='out_trade_no' value='" + htmlEscape(payNo) + "'>"
                + "<input type='hidden' name='trade_no' value='" + htmlEscape(tradeNo) + "'>"
                + "<input type='hidden' name='trade_status' value='TRADE_SUCCESS'>"
                + "<input type='hidden' name='total_amount' value='" + htmlEscape(totalAmount) + "'>"
                + "<input type='hidden' name='sign' value='" + sign + "'>"
                + "<input type='submit' value='模拟支付'>"
                + "</form>";

        log.info("支付创建成功（模拟模式）: payNo={}, orderNo={}", payNo, request.getOrderNo());

        return PayResponse.builder()
                .payNo(payNo)
                .orderNo(request.getOrderNo())
                .amount(totalAmount)
                .payForm(mockForm)
                .qrCode("http://mock-qr-code")
                .build();
    }

    /**
     * 处理支付回调通知
     */
    public String handleNotify(Map<String, String> params) {
        String payNo = params.get("out_trade_no");
        String tradeNo = params.get("trade_no");
        String tradeStatus = params.get("trade_status");
        String sign = params.get("sign");

        // 验签：模拟模式回调必须携带 createPay 签发的 HMAC 签名。
        // 未验签前任何参数都不可信，验签失败一律拒绝更新支付状态（防伪造回调）
        if (!hmacVerify(payNo, tradeNo, tradeStatus, params.get("total_amount"), sign)) {
            log.warn("支付回调验签失败，拒绝更新: payNo={}, tradeNo={}", payNo, tradeNo);
            return "failure";
        }

        log.info("支付回调: payNo={}, tradeNo={}, status={}", payNo, tradeNo, tradeStatus);

        if (!"TRADE_SUCCESS".equals(tradeStatus)) {
            return "success";
        }

        // 幂等处理：按 trade_no 去重
        PayRecord existing = payRecordMapper.findByTradeNo(tradeNo);
        if (existing != null) {
            log.info("回调已处理过: tradeNo={}", tradeNo);
            return "success";
        }

        String orderNo;
        // ^ 仅更新支付记录（操作短平快）
        synchronized (this) {
            PayRecord record = payRecordMapper.findByPayNo(payNo);
            if (record == null) {
                log.error("支付记录不存在: payNo={}", payNo);
                return "failure";
            }
            if (record.getStatus() != 0) {
                return "success";
            }
            record.setTradeNo(tradeNo);
            record.setStatus(1);
            record.setPayTime(LocalDateTime.now());
            record.setNotifyTime(LocalDateTime.now());
            payRecordMapper.updateById(record);
            orderNo = record.getOrderNo();
        }

        // Feign 调用放在事务外，避免长连接
        notifyOrderService(orderNo);

        return "success";
    }

    /** 用配置的 mockSecret 对回调参数做 HMAC-SHA256 签名（覆盖 payNo|tradeNo|tradeStatus|totalAmount） */
    private String hmacSign(String payNo, String tradeNo, String tradeStatus, String totalAmount) {
        return hmacHex(payNo + "|" + tradeNo + "|" + tradeStatus + "|" + totalAmount);
    }

    /** 恒定时间比较签名，防时序攻击；任一参数缺失即拒绝 */
    private boolean hmacVerify(String payNo, String tradeNo, String tradeStatus,
                               String totalAmount, String expected) {
        if (payNo == null || tradeNo == null || tradeStatus == null
                || totalAmount == null || expected == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                hmacHex(payNo + "|" + tradeNo + "|" + tradeStatus + "|" + totalAmount)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String hmacHex(String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    payConfig.getMockSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(
                    mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BusinessException(500, "HMAC 计算失败", e);
        }
    }

    /** HTML 转义，防止表单值注入 HTML（XSS 向量） */
    private String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private void notifyOrderService(String orderNo) {
        try {
            com.ecommerce.common.Result<Void> result = orderFeignClient.payByOrderNo(orderNo);
            if (result != null && result.getCode() == 200) {
                log.info("订单支付状态已更新: orderNo={}", orderNo);
            } else {
                log.error("订单服务更新支付状态失败");
            }
        } catch (Exception e) {
            log.error("Feign 调用订单服务失败", e);
            // MQ 补偿
            try {
                rocketMQTemplate.syncSend("payment-success-topic", orderNo);
            } catch (Exception mqErr) {
                log.error("MQ 补偿消息发送失败", mqErr);
            }
        }
    }
}
