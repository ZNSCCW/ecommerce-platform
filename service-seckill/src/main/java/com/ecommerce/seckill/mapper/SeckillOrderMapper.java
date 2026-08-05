package com.ecommerce.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.seckill.entity.SeckillOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SeckillOrderMapper extends BaseMapper<SeckillOrder> {

    @Select("SELECT COUNT(*) FROM t_seckill_order " +
            "WHERE user_id = #{userId} AND activity_id = #{activityId} AND deleted = 0")
    int countByUserAndActivity(Long userId, Long activityId);
}
