package com.ecommerce.payment.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PayRequest {

    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    @NotNull(message = "支付金额不能为空")
    @DecimalMin(value = "0.01", message = "支付金额必须大于0")
    @DecimalMax(value = "99999999.99", message = "支付金额超上限")
    private BigDecimal amount;

    /** 1-支付宝 2-微信 */
    @NotNull(message = "支付渠道不能为空")
    @Min(value = 1, message = "支付渠道不合法")
    @Max(value = 2, message = "支付渠道不合法")
    private Integer channel = 1;
}
