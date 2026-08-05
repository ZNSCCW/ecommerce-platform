package com.ecommerce.product.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_spu")
public class Spu {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String brand;

    private String description;

    private String mainImage;

    private Long categoryId;

    private Integer status;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
