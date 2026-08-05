package com.ecommerce.product.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ecommerce.common.Result;
import com.ecommerce.product.dto.SpuPageRequest;
import com.ecommerce.product.dto.SpuVO;
import com.ecommerce.product.entity.Category;
import com.ecommerce.product.entity.Sku;
import com.ecommerce.product.entity.Spu;
import com.ecommerce.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    // ==================== SPU ====================

    @GetMapping("/spu/page")
    public Result<IPage<Spu>> page(SpuPageRequest request) {
        return Result.success(productService.page(request));
    }

    @GetMapping("/spu/{id}")
    public Result<SpuVO> getDetail(@PathVariable Long id) {
        return Result.success(productService.getSpuDetail(id));
    }

    @PostMapping("/spu")
    public Result<Long> create(@RequestBody Spu spu) {
        return Result.success(productService.createSpu(spu));
    }

    @PutMapping("/spu")
    public Result<Void> update(@RequestBody Spu spu) {
        productService.updateSpu(spu);
        return Result.success();
    }

    @DeleteMapping("/spu/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        productService.deleteSpu(id);
        return Result.success();
    }

    @PutMapping("/spu/{id}/status")
    public Result<Void> toggleStatus(@PathVariable Long id, @RequestParam Integer status) {
        productService.toggleStatus(id, status);
        return Result.success();
    }

    // ==================== SKU ====================

    @GetMapping("/sku/spu/{spuId}")
    public Result<List<Sku>> getSkus(@PathVariable Long spuId) {
        return Result.success(productService.getSkusBySpuId(spuId));
    }

    @PostMapping("/sku")
    public Result<Long> createSku(@RequestBody Sku sku) {
        return Result.success(productService.createSku(sku));
    }

    @PutMapping("/sku")
    public Result<Void> updateSku(@RequestBody Sku sku) {
        productService.updateSku(sku);
        return Result.success();
    }

    @DeleteMapping("/sku/{id}")
    public Result<Void> deleteSku(@PathVariable Long id) {
        productService.deleteSku(id);
        return Result.success();
    }

    // ==================== 库存操作（Feign 内部调用） ====================

    @PutMapping("/sku/stock/deduct")
    public Result<Boolean> deductStock(@RequestParam Long skuId, @RequestParam Integer quantity) {
        return Result.success(productService.deductStock(skuId, quantity));
    }

    @PutMapping("/sku/stock/restore")
    public Result<Boolean> restoreStock(@RequestParam Long skuId, @RequestParam Integer quantity) {
        return Result.success(productService.restoreStock(skuId, quantity));
    }

    // ==================== 分类 ====================

    @GetMapping("/category/tree")
    public Result<List<Category>> categoryTree() {
        return Result.success(productService.getCategoryTree());
    }

    @GetMapping("/category/{id}")
    public Result<Category> getCategory(@PathVariable Long id) {
        return Result.success(productService.getCategoryById(id));
    }
}
