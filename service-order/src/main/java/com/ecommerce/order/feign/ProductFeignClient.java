package com.ecommerce.order.feign;

import com.ecommerce.common.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 调用商品服务的 Feign 接口
 */
@FeignClient(name = "service-product", path = "/api/product")
public interface ProductFeignClient {

    @PutMapping("/sku/stock/deduct")
    Result<Boolean> deductStock(@RequestParam("skuId") Long skuId,
                                 @RequestParam("quantity") Integer quantity);

    @PutMapping("/sku/stock/restore")
    Result<Boolean> restoreStock(@RequestParam("skuId") Long skuId,
                                  @RequestParam("quantity") Integer quantity);
}
