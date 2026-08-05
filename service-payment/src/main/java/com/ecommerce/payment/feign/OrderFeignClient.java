package com.ecommerce.payment.feign;

import com.ecommerce.common.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 调用订单服务更新支付状态
 */
@FeignClient(name = "service-order", path = "/api/order")
public interface OrderFeignClient {

    @PostMapping("/pay-by-order-no")
    Result<Void> payByOrderNo(@RequestParam("orderNo") String orderNo);
}
