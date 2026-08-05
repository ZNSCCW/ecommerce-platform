package com.ecommerce.seckill.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀订单表（秒杀成功生成的订单）
 */
@Data
@TableName("t_seckill_order")
public class SeckillOrder {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long userId;

    private Long activityId;

    private Long skuId;

    private BigDecimal seckillPrice;

    private Integer quantity;

    /** 0-待支付 1-已支付 2-已取消 */
    private Integer status;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
