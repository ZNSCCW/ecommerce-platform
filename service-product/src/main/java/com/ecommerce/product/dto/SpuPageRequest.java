package com.ecommerce.product.dto;

import lombok.Data;

@Data
public class SpuPageRequest {

    private Long categoryId;

    private String keyword;

    private Integer status;

    private Integer page = 1;

    private Integer size = 10;
}
