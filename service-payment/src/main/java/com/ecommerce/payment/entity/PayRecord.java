package com.ecommerce.payment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_pay_record")
public class PayRecord {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String payNo;

    private String orderNo;

    private Long userId;

    private BigDecimal payAmount;

    /** 1-支付宝 2-微信 */
    private Integer payChannel;

    /** 三方交易号（支付宝返回） */
    private String tradeNo;

    /** 0-待支付 1-支付成功 2-支付失败 3-已退款 */
    private Integer status;

    private LocalDateTime payTime;

    private LocalDateTime notifyTime;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
