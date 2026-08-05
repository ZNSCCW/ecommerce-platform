package com.ecommerce.seckill.feign;

import com.ecommerce.common.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 调用商品服务获取 SKU 信息和扣减库存
 */
@FeignClient(name = "service-product", path = "/api/product")
public interface ProductFeignClient {

    @PutMapping("/internal/sku/stock/deduct")
    Result<Boolean> deductStock(@RequestParam("skuId") Long skuId,
                                 @RequestParam("quantity") Integer quantity);
}
