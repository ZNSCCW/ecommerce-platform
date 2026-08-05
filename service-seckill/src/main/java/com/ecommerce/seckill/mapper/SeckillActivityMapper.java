package com.ecommerce.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.seckill.entity.SeckillActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SeckillActivityMapper extends BaseMapper<SeckillActivity> {

    @Select("SELECT * FROM t_seckill_activity " +
            "WHERE status = 1 AND deleted = 0 " +
            "AND start_time <= #{now} AND end_time >= #{now}")
    List<SeckillActivity> findActiveActivities(LocalDateTime now);
}
