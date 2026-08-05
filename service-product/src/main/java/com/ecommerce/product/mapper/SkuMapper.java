package com.ecommerce.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.product.entity.Sku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SkuMapper extends BaseMapper<Sku> {

    @Select("SELECT * FROM t_sku WHERE spu_id = #{spuId} AND deleted = 0")
    List<Sku> findBySpuId(Long spuId);

    /**
     * 原子扣减库存：stock -= quantity，仅当 stock >= quantity 时成功
     * @return 影响行数（0=库存不足或商品不存在）
     */
    @Update("UPDATE t_sku SET stock = stock - #{quantity} WHERE id = #{skuId} AND stock >= #{quantity} AND deleted = 0")
    int updateStock(Long skuId, Integer quantity);

    /**
     * 恢复库存
     */
    @Update("UPDATE t_sku SET stock = stock + #{quantity} WHERE id = #{skuId} AND deleted = 0")
    int restoreStock(Long skuId, Integer quantity);
}
