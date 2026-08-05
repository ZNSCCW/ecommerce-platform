package com.ecommerce.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.seckill.entity.SeckillProduct;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SeckillProductMapper extends BaseMapper<SeckillProduct> {

    @Select("SELECT * FROM t_seckill_product WHERE activity_id = #{activityId} AND deleted = 0")
    List<SeckillProduct> findByActivityId(Long activityId);
}
