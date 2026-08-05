package com.ecommerce.order.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderVO {

    private Long id;

    private String orderNo;

    private Long userId;

    private BigDecimal totalAmount;

    private BigDecimal payAmount;

    private Integer status;

    private String statusDesc;

    private LocalDateTime payTime;

    private LocalDateTime cancelTime;

    private String cancelReason;

    private LocalDateTime expireTime;

    private String remark;

    private List<OrderItemVO> items;

    private LocalDateTime createdAt;
}
