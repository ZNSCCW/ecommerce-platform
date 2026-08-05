package com.ecommerce.order.dto;

import lombok.Data;

@Data
public class OrderPageRequest {

    private Integer status;

    private Integer page = 1;

    private Integer size = 10;
}
