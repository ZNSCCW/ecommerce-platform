package com.ecommerce.payment.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 支付宝/微信支付配置
 *
 * 支付宝沙箱配置获取：https://open.alipay.com/ → 沙箱环境
 * 集成真实 SDK 时需：
 * 1. 取消 service-payment/pom.xml 中 alipay-sdk-java 依赖的注释
 * 2. 在父 pom.xml 中添加阿里云仓库
 * 3. 填入下方真实的沙箱凭证
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "pay")
public class PayConfig {

    /** 支付宝沙箱 APPID */
    private String alipayAppId = "2021000000000000";  // TODO: 替换

    /** 支付宝异步通知地址 */
    private String notifyUrl = "https://your-domain.com/api/payment/notify";  // TODO: 替换

    /** 支付宝同步跳转地址 */
    private String returnUrl = "https://your-domain.com/#/payment/success";  // TODO: 替换

    /** 模拟支付模式（true=不调真实支付宝，直接返回成功） */
    private boolean mockMode = true;
}
