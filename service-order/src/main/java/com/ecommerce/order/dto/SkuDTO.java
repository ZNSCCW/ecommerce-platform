package com.ecommerce.order.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单服务侧 SKU 视图（Feign 调用商品服务返回）。
 * 字段与商品服务 Sku 对齐，避免跨模块依赖实体类。
 */
@Data
public class SkuDTO {
    private Long id;
    private Long spuId;
    private String name;
    private String specs;
    private BigDecimal price;
    private Integer stock;
    private String image;
}
