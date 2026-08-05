package com.ecommerce.order.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ecommerce.common.Result;
import com.ecommerce.order.dto.CreateOrderRequest;
import com.ecommerce.order.dto.OrderPageRequest;
import com.ecommerce.order.dto.OrderVO;
import com.ecommerce.order.entity.Order;
import com.ecommerce.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * 创建订单
     */
    @PostMapping
    public Result<OrderVO> create(@RequestAttribute("userId") Long userId,
                                   @Valid @RequestBody CreateOrderRequest request) {
        return Result.success(orderService.createOrder(userId, request));
    }

    /**
     * 订单详情
     */
    @GetMapping("/{id}")
    public Result<OrderVO> detail(@RequestAttribute("userId") Long userId,
                                   @PathVariable Long id) {
        return Result.success(orderService.getOrderDetail(id, userId));
    }

    /**
     * 我的订单列表
     */
    @GetMapping("/page")
    public Result<IPage<Order>> page(@RequestAttribute("userId") Long userId,
                                      OrderPageRequest request) {
        return Result.success(orderService.page(userId, request));
    }

    /**
     * 取消订单
     */
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@RequestAttribute("userId") Long userId,
                                @PathVariable Long id,
                                @RequestParam(required = false) String reason) {
        orderService.cancel(id, userId, reason);
        return Result.success();
    }

    /**
     * 支付回调（内部调用）
     */
    @PostMapping("/{id}/pay")
    public Result<Void> pay(@PathVariable Long id) {
        orderService.pay(id);
        return Result.success();
    }

    @PostMapping("/pay-by-order-no")
    public Result<Void> payByOrderNo(@RequestParam String orderNo) {
        orderService.payByOrderNo(orderNo);
        return Result.success();
    }
}
