package com.ecommerce.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayResponse {

    /** 支付流水号 */
    private String payNo;

    /** 订单号 */
    private String orderNo;

    /** 支付金额 */
    private String amount;

    /** 支付宝表单（HTML）或二维码 URL */
    private String payForm;

    /** 二维码 URL */
    private String qrCode;
}
