package com.ecommerce.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class OrderPageRequest {

    private Integer status;

    @Min(value = 1, message = "页码至少为1")
    private Integer page = 1;

    @Min(value = 1, message = "每页至少1条")
    @Max(value = 100, message = "每页最多100条")
    private Integer size = 10;
}
