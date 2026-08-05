package com.ecommerce.seckill.controller;

import com.ecommerce.common.Result;
import com.ecommerce.seckill.dto.SeckillRequest;
import com.ecommerce.seckill.dto.SeckillResponse;
import com.ecommerce.seckill.entity.SeckillActivity;
import com.ecommerce.seckill.entity.SeckillProduct;
import com.ecommerce.seckill.service.SeckillService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;

    /**
     * 秒杀接口
     */
    @PostMapping
    public Result<SeckillResponse> seckill(@RequestAttribute("userId") Long userId,
                                            @Valid @RequestBody SeckillRequest request) {
        return Result.success(seckillService.seckill(userId, request));
    }

    /**
     * 获取当前进行中的活动
     */
    @GetMapping("/activities")
    public Result<List<SeckillActivity>> getActivities() {
        return Result.success(seckillService.getActiveActivities());
    }

    /**
     * 获取活动的秒杀商品
     */
    @GetMapping("/activities/{activityId}/products")
    public Result<List<SeckillProduct>> getProducts(@PathVariable Long activityId) {
        return Result.success(seckillService.getActivityProducts(activityId));
    }

    /**
     * 查询实时库存
     */
    @GetMapping("/stock/{activityId}/{skuId}")
    public Result<Integer> getStock(@PathVariable Long activityId, @PathVariable Long skuId) {
        return Result.success(seckillService.getStock(activityId, skuId));
    }

    /**
     * 预热库存（活动开始前调用）
     */
    @PostMapping("/warm-up/{activityId}")
    public Result<Void> warmUp(@PathVariable Long activityId) {
        seckillService.warmUpStock(activityId);
        return Result.success();
    }
}
