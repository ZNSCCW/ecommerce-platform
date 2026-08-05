package com.ecommerce.product.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 单个 SKU 视图对象
 */
@Data
public class SkuVO {

    private Long id;

    private String name;

    private String specs;

    private BigDecimal price;

    private Integer stock;

    private String image;
}
