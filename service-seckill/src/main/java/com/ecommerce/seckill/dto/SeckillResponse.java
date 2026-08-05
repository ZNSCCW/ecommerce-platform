package com.ecommerce.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillResponse {

    /** 排队中 */
    public static final int QUEUED = 1;
    /** 秒杀成功 */
    public static final int SUCCESS = 2;
    /** 失败 */
    public static final int FAILED = 3;

    private int code;
    private String msg;
    private String orderNo;
}
