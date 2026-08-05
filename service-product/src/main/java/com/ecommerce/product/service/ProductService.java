package com.ecommerce.product.service;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.common.BusinessException;
import com.ecommerce.common.ResultCode;
import com.ecommerce.product.dto.SkuVO;
import com.ecommerce.product.dto.SpuPageRequest;
import com.ecommerce.product.dto.SpuVO;
import com.ecommerce.product.entity.Category;
import com.ecommerce.product.entity.Sku;
import com.ecommerce.product.entity.Spu;
import com.ecommerce.product.mapper.CategoryMapper;
import com.ecommerce.product.mapper.SkuMapper;
import com.ecommerce.product.mapper.SpuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final SpuMapper spuMapper;
    private final SkuMapper skuMapper;
    private final CategoryMapper categoryMapper;
    private final ProductCacheService cacheService;
    private final SearchService searchService;

    // ==================== SPU CRUD ====================

    public IPage<Spu> page(SpuPageRequest request) {
        Page<Spu> page = new Page<>(request.getPage(), request.getSize());
        LambdaQueryWrapper<Spu> wrapper = new LambdaQueryWrapper<Spu>()
                .eq(request.getCategoryId() != null, Spu::getCategoryId, request.getCategoryId())
                .eq(request.getStatus() != null, Spu::getStatus, request.getStatus())
                .like(request.getKeyword() != null && !request.getKeyword().isEmpty(),
                        Spu::getName, request.getKeyword())
                .orderByDesc(Spu::getCreatedAt);
        return spuMapper.selectPage(page, wrapper);
    }

    public SpuVO getSpuDetail(Long spuId) {
        // 先查缓存
        SpuVO cached = cacheService.getSpuCache(spuId);
        if (cached != null) {
            return cached;
        }

        // 缓存未命中 → 走分布式锁防止击穿
        SpuVO locked = cacheService.loadWithLock(spuId);
        if (locked != null) {
            return locked;
        }

        // 双重检测后仍未命中（如空值缓存），查数据库
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null) {
            // 空值缓存防穿透
            cacheService.setNullCache(spuId);
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        if (spu.getStatus() == 0) {
            throw new BusinessException(ResultCode.PRODUCT_SOLD_OUT);
        }

        SpuVO vo = buildSpuVO(spu);
        cacheService.setSpuCache(spuId, vo);
        return vo;
    }

    @Transactional
    public Long createSpu(Spu spu) {
        spu.setStatus(0); // 默认下架
        spuMapper.insert(spu);
        return spu.getId();
    }

    @Transactional
    public void updateSpu(Spu spu) {
        spuMapper.updateById(spu);
        // 更新后清除缓存
        cacheService.evictSpuCache(spu.getId());
        // 同步 ES（如果已上架）
        if (spu.getStatus() != null && spu.getStatus() == 1) {
            searchService.syncToEs(spu.getId());
        }
    }

    @Transactional
    public void deleteSpu(Long spuId) {
        spuMapper.deleteById(spuId);
        cacheService.evictSpuCache(spuId);
        searchService.deleteFromEs(spuId);
    }

    @Transactional
    public void toggleStatus(Long spuId, Integer status) {
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        spu.setStatus(status);
        spuMapper.updateById(spu);

        cacheService.evictSpuCache(spuId);
        if (status == 1) {
            searchService.syncToEs(spuId);
        } else {
            searchService.deleteFromEs(spuId);
        }
    }

    // ==================== SKU CRUD ====================

    public List<Sku> getSkusBySpuId(Long spuId) {
        return skuMapper.findBySpuId(spuId);
    }

    @Transactional
    public Long createSku(Sku sku) {
        skuMapper.insert(sku);
        cacheService.evictSpuCache(sku.getSpuId());
        return sku.getId();
    }

    @Transactional
    public void updateSku(Sku sku) {
        skuMapper.updateById(sku);
        cacheService.evictSpuCache(sku.getSpuId());
    }

    @Transactional
    public void deleteSku(Long skuId) {
        Sku sku = skuMapper.selectById(skuId);
        if (sku != null) {
            skuMapper.deleteById(skuId);
            cacheService.evictSpuCache(sku.getSpuId());
        }
    }

    // ==================== 分类 ====================

    public List<Category> getCategoryTree() {
        List<Category> all = categoryMapper.selectList(
                new LambdaQueryWrapper<Category>().orderByAsc(Category::getSort));
        // 只返回顶级分类（parentId 为 null），前端自行递归渲染
        return all.stream()
                .filter(c -> c.getParentId() == null)
                .collect(Collectors.toList());
    }

    public Category getCategoryById(Long id) {
        return categoryMapper.selectById(id);
    }

    // ==================== 内部方法 ====================

    Spu getSpuDetailInternal(Long spuId) {
        return spuMapper.selectById(spuId);
    }

    SpuVO buildSpuVO(Spu spu) {
        SpuVO vo = new SpuVO();
        BeanUtil.copyProperties(spu, vo, "categoryName");

        // 分类名称
        Category category = categoryMapper.selectById(spu.getCategoryId());
        vo.setCategoryName(category != null ? category.getName() : "");

        // SKU 列表
        List<Sku> skuList = skuMapper.findBySpuId(spu.getId());
        List<SkuVO> skuVOS = skuList.stream().map(sku -> {
            SkuVO skuVo = new SkuVO();
            BeanUtil.copyProperties(sku, skuVo);
            return skuVo;
        }).collect(Collectors.toList());
        vo.setSkuList(skuVOS);

        return vo;
    }

    // ==================== 库存操作（其他服务调用） ====================

    @Transactional
    public boolean deductStock(Long skuId, Integer quantity) {
        // 原子扣减：使用 SQL UPDATE stock = stock - #{qty} WHERE stock >= #{qty}
        // 避免先查后改导致的并发超卖
        int affected = skuMapper.updateStock(skuId, quantity);
        if (affected > 0) {
            cacheService.evictSpuCache(skuId);
            return true;
        }
        return false;
    }

    @Transactional
    public boolean restoreStock(Long skuId, Integer quantity) {
        int affected = skuMapper.restoreStock(skuId, quantity);
        if (affected > 0) {
            cacheService.evictSpuCache(skuId);
            return true;
        }
        return false;
    }
}
