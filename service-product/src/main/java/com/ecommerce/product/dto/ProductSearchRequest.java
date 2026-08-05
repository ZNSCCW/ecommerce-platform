package com.ecommerce.product.dto;

import lombok.Data;

import java.util.List;

/**
 * 商品搜索请求
 */
@Data
public class ProductSearchRequest {

    private String keyword;

    private Long categoryId;

    private Double minPrice;

    private Double maxPrice;

    private String sortBy;  // price_asc, price_desc, sale_desc

    private Integer page = 1;

    private Integer size = 20;
}
