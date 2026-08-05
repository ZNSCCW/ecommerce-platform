package com.ecommerce.seckill.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_seckill_product")
public class SeckillProduct {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long activityId;

    private Long skuId;

    private BigDecimal seckillPrice;

    private Integer seckillStock;

    private Integer limitPerUser;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
