package com.ecommerce.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.payment.entity.PayRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PayRecordMapper extends BaseMapper<PayRecord> {

    @Select("SELECT * FROM t_pay_record WHERE trade_no = #{tradeNo} AND deleted = 0")
    PayRecord findByTradeNo(String tradeNo);

    @Select("SELECT * FROM t_pay_record WHERE order_no = #{orderNo} AND deleted = 0")
    PayRecord findByOrderNo(String orderNo);

    @Select("SELECT * FROM t_pay_record WHERE pay_no = #{payNo} AND deleted = 0")
    PayRecord findByPayNo(String payNo);
}
