package com.ecommerce.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /** 原子更新订单状态（带状态校验，防止并发状态错乱） */
    @Update("UPDATE t_order SET status = #{newStatus}, " +
            "pay_time = NOW(), updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = #{oldStatus}")
    int updateStatusWithLock(Long orderId, Integer oldStatus, Integer newStatus);
}
