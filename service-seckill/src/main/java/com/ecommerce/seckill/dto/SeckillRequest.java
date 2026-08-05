package com.ecommerce.seckill.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SeckillRequest {

    @NotNull(message = "活动ID不能为空")
    private Long activityId;

    @NotNull(message = "商品SKU不能为空")
    private Long skuId;
}
