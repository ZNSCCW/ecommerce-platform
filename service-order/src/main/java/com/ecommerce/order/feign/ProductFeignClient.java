package com.ecommerce.order.feign;

import com.ecommerce.common.Result;
import com.ecommerce.order.dto.SkuDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 调用商品服务的 Feign 接口
 */
@FeignClient(name = "service-product", path = "/api/product")
public interface ProductFeignClient {

    @PutMapping("/internal/sku/stock/deduct")
    Result<Boolean> deductStock(@RequestParam("skuId") Long skuId,
                                 @RequestParam("quantity") Integer quantity);

    @PutMapping("/internal/sku/stock/restore")
    Result<Boolean> restoreStock(@RequestParam("skuId") Long skuId,
                                  @RequestParam("quantity") Integer quantity);

    /**
     * 内部接口：获取 SKU 真实价格/名称/图片（下单时使用，避免订单金额写死）
     */
    @GetMapping("/internal/sku/{id}")
    Result<SkuDTO> getSku(@PathVariable("id") Long skuId);
}
