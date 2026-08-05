package com.ecommerce.order.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.common.BusinessException;
import com.ecommerce.common.Result;
import com.ecommerce.common.ResultCode;
import com.ecommerce.order.dto.*;
import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderItem;
import com.ecommerce.order.feign.ProductFeignClient;
import com.ecommerce.order.mapper.OrderItemMapper;
import com.ecommerce.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductFeignClient productFeignClient;
    private final RocketMQTemplate rocketMQTemplate;

    /** 订单状态机：Map<当前状态, Map<目标状态, 操作描述>> */
    private static final Map<Integer, Map<Integer, String>> STATE_MACHINE = new HashMap<>();

    static {
        // 0-待支付 → 1-已支付 / 4-已取消
        STATE_MACHINE.put(0, new HashMap<>() {{
            put(1, "支付");
            put(4, "取消");
        }});
        // 1-已支付 → 2-已发货
        STATE_MACHINE.put(1, new HashMap<>() {{
            put(2, "发货");
        }});
        // 2-已发货 → 3-已完成
        STATE_MACHINE.put(2, new HashMap<>() {{
            put(3, "完成");
        }});
    }

    /** 订单状态描述 */
    private static final Map<Integer, String> STATUS_DESC = Map.of(
            0, "待支付", 1, "已支付", 2, "已发货", 3, "已完成", 4, "已取消"
    );

    // ==================== 下单 ====================

    /**
     * RocketMQ 延迟等级对应时间：
     * 1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h
     * 等级 16 = 30 分钟
     */
    private static final int DELAY_LEVEL_30MIN = 16;

    public OrderVO createOrder(Long userId, CreateOrderRequest request) {
        // Step 1: 扣减库存（事务外，Feign 远程调用）
        for (CreateOrderRequest.OrderItemRequest item : request.getItems()) {
            Result<Boolean> result = productFeignClient.deductStock(item.getSkuId(), item.getQuantity());
            if (result == null || !Boolean.TRUE.equals(result.getData())) {
                rollbackStock(request.getItems(), item.getSkuId());
                throw new BusinessException(ResultCode.STOCK_NOT_ENOUGH);
            }
        }

        // Step 2: 本地事务 — 生成订单 + 明细
        OrderVO orderVO = doCreateOrder(userId, request);

        // Step 3: 发送延迟消息（事务外）
        rocketMQTemplate.syncSend(
                "order-cancel-topic",
                MessageBuilder.withPayload(String.valueOf(orderVO.getId())).build(),
                3000,
                DELAY_LEVEL_30MIN
        );

        log.info("订单创建成功: orderNo={}, userId={}", orderVO.getOrderNo(), userId);
        return orderVO;
    }

    @Transactional
    public OrderVO doCreateOrder(Long userId, CreateOrderRequest request) {
        // 生成订单（事务内只操作本地 DB）
        Snowflake snowflake = IdUtil.getSnowflake(1, 1);
        Order order = new Order();
        order.setOrderNo(String.valueOf(snowflake.nextId()));
        order.setUserId(userId);
        order.setStatus(0);
        order.setExpireTime(LocalDateTime.now().plusMinutes(30));

        // 计算金额
        BigDecimal total = BigDecimal.ZERO;
        for (CreateOrderRequest.OrderItemRequest item : request.getItems()) {
            BigDecimal unitPrice = BigDecimal.valueOf(100); // TODO: 从商品服务获取价格
            BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(item.getQuantity()));
            total = total.add(subtotal);
        }
        order.setTotalAmount(total);
        order.setPayAmount(total);
        order.setRemark(request.getRemark());
        orderMapper.insert(order);

        // 3. 创建订单明细（order已插入，order.getId()可用）
        for (CreateOrderRequest.OrderItemRequest item : request.getItems()) {
            BigDecimal unitPrice = BigDecimal.valueOf(100); // TODO: 从商品服务获取价格

            OrderItem orderItem = new OrderItem();
            orderItem.setOrderId(order.getId());
            orderItem.setSkuId(item.getSkuId());
            orderItem.setSkuName("商品名称");    // TODO: 从商品服务获取
            orderItem.setSkuImage("");           // TODO: 从商品服务获取
            orderItem.setPrice(unitPrice);
            orderItem.setQuantity(item.getQuantity());
            orderItem.setSubtotal(unitPrice.multiply(BigDecimal.valueOf(item.getQuantity())));
            orderItemMapper.insert(orderItem);
        }

        // 延迟消息由 createOrder() 在事务外发送，避免重复+幽灵消息
        return buildOrderVO(order);
    }

    /**
     * 库存回滚（部分扣减失败时回滚已成功的）
     */
    private void rollbackStock(List<CreateOrderRequest.OrderItemRequest> items, Long failedSkuId) {
        for (CreateOrderRequest.OrderItemRequest item : items) {
            if (item.getSkuId().equals(failedSkuId)) {
                break; // 失败的 SKU 及其后面的都没扣成功
            }
            try {
                productFeignClient.restoreStock(item.getSkuId(), item.getQuantity());
            } catch (Exception e) {
                log.error("库存回滚失败: skuId={}", item.getSkuId(), e);
            }
        }
    }

    // ==================== 状态变更 ====================

    @Transactional
    public void updateStatus(Long orderId, Integer targetStatus, String... extraInfo) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }

        Integer currentStatus = order.getStatus();
        Map<Integer, String> allowedTransitions = STATE_MACHINE.get(currentStatus);
        if (allowedTransitions == null || !allowedTransitions.containsKey(targetStatus)) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR);
        }

        int updated = orderMapper.updateStatusWithLock(orderId, currentStatus, targetStatus);
        if (updated == 0) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR);
        }
    }

    /**
     * 支付回调
     */
    @Transactional
    public void pay(Long orderId) {
        updateStatus(orderId, 1, LocalDateTime.now().toString());
    }

    @Transactional
    public void payByOrderNo(String orderNo) {
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        pay(order.getId());
    }

    /**
     * 取消订单（用户主动取消）
     */
    @Transactional
    public void doCancelDb(Long orderId, String reason) {
        updateStatus(orderId, 4);
        Order update = new Order();
        update.setId(orderId);
        update.setCancelReason(reason);
        update.setCancelTime(LocalDateTime.now());
        orderMapper.updateById(update);
    }

    public void cancel(Long orderId, Long userId, String reason) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }

        // Step 1: 本地事务更新订单状态（仅操作 DB）
        doCancelDb(orderId, reason);

        // Step 2: Feign 调用恢复库存（事务外）
        List<OrderItem> items = orderItemMapper.findByOrderId(orderId);
        for (OrderItem item : items) {
            try {
                productFeignClient.restoreStock(item.getSkuId(), item.getQuantity());
            } catch (Exception e) {
                log.error("取消订单恢复库存失败: orderId={}, skuId={}", orderId, item.getSkuId(), e);
            }
        }
    }

    // ==================== 超时取消（RocketMQ 消费者调用） ====================

    @Transactional
    public void doCancelExpiredDb(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null || order.getStatus() != 0) {
            return;
        }
        updateStatus(orderId, 4);
        Order update = new Order();
        update.setId(orderId);
        update.setCancelReason("支付超时，系统自动取消");
        update.setCancelTime(LocalDateTime.now());
        orderMapper.updateById(update);
    }

    public void cancelExpired(Long orderId) {
        doCancelExpiredDb(orderId);

        List<OrderItem> items = orderItemMapper.findByOrderId(orderId);
        for (OrderItem item : items) {
            try {
                productFeignClient.restoreStock(item.getSkuId(), item.getQuantity());
            } catch (Exception e) {
                log.error("超时取消恢复库存失败: orderId={}, skuId={}", orderId, item.getSkuId(), e);
            }
        }
    }

    // ==================== 查询 ====================

    public OrderVO getOrderDetail(Long orderId, Long userId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        return buildOrderVO(order);
    }

    public OrderVO getByOrderNo(String orderNo) {
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        return buildOrderVO(order);
    }

    public IPage<Order> page(Long userId, OrderPageRequest request) {
        Page<Order> page = new Page<>(request.getPage(), request.getSize());
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .eq(request.getStatus() != null, Order::getStatus, request.getStatus())
                .orderByDesc(Order::getCreatedAt);
        return orderMapper.selectPage(page, wrapper);
    }

    // ==================== 内部方法 ====================

    public String getStatusDesc(Integer status) {
        return STATUS_DESC.getOrDefault(status, "未知");
    }

    private OrderVO buildOrderVO(Order order) {
        OrderVO vo = new OrderVO();
        BeanUtil.copyProperties(order, vo, "items");
        vo.setStatusDesc(STATUS_DESC.getOrDefault(order.getStatus(), "未知"));

        List<OrderItem> items = orderItemMapper.findByOrderId(order.getId());
        List<OrderItemVO> itemVOS = items.stream().map(item -> {
            OrderItemVO itemVo = new OrderItemVO();
            BeanUtil.copyProperties(item, itemVo);
            return itemVo;
        }).collect(Collectors.toList());
        vo.setItems(itemVOS);

        return vo;
    }
}
