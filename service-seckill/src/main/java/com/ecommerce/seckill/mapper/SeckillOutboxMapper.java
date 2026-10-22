package com.ecommerce.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.seckill.entity.SeckillOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SeckillOutboxMapper extends BaseMapper<SeckillOutbox> {

    /** 取一批待投递消息（走 idx_status_id，按 id 顺序保证同一用户先入先出） */
    @Select("SELECT * FROM t_seckill_outbox WHERE status = 0 ORDER BY id LIMIT #{limit}")
    List<SeckillOutbox> findPending(@Param("limit") int limit);
}
