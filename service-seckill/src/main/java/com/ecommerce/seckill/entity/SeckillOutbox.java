package com.ecommerce.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 秒杀本地消息表（Transactional Outbox）。
 *
 * 秒杀请求在 Redis 扣减成功后只写一条 outbox，由后台任务异步投递到 RocketMQ，
 * 请求线程不再同步等待 MQ 回执；投递失败会重试，超过上限则回补 Redis 库存。
 */
@Data
@TableName("t_seckill_outbox")
public class SeckillOutbox {

    /** 0=待投递 1=已投递 2=投递失败（已补偿） */
    public static final int PENDING = 0;
    public static final int SENT = 1;
    public static final int FAILED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long activityId;

    private Long skuId;

    private Long userId;

    /** MQ 消息体：activityId:skuId:userId:price */
    private String payload;

    private Integer status;

    private Integer retryCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
