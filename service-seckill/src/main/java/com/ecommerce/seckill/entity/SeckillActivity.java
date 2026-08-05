package com.ecommerce.seckill.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_seckill_activity")
public class SeckillActivity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 0-待开始 1-进行中 2-已结束 */
    private Integer status;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
