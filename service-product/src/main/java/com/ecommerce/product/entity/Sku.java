package com.ecommerce.product.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_sku")
public class Sku {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spuId;

    private String name;

    private String specs;

    private BigDecimal price;

    private Integer stock;

    private String image;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
