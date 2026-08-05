package com.ecommerce.product.dto;

import lombok.Data;

import java.util.List;

/**
 * SPU 视图对象（含 SKU 列表）
 */
@Data
public class SpuVO {

    private Long id;

    private String name;

    private String brand;

    private String description;

    private String mainImage;

    private Long categoryId;

    private String categoryName;

    private Integer status;

    private List<SkuVO> skuList;
}
