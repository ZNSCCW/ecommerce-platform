package com.ecommerce.common;

import lombok.Getter;

/**
 * 业务异常码
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    FAILED(500, "操作失败"),
    VALIDATE_FAILED(400, "参数校验失败"),
    UNAUTHORIZED(401, "未登录或Token已过期"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),

    // 用户模块
    USER_NOT_FOUND(1001, "用户不存在"),
    USER_PASSWORD_ERROR(1002, "密码错误"),
    USER_DISABLED(1003, "账号已禁用"),
    USER_EXIST(1004, "账号已存在"),
    TOKEN_INVALID(1005, "Token无效"),

    // 商品模块
    PRODUCT_NOT_FOUND(2001, "商品不存在"),
    PRODUCT_SOLD_OUT(2002, "商品已下架"),
    STOCK_NOT_ENOUGH(2003, "库存不足"),

    // 订单模块
    ORDER_NOT_FOUND(3001, "订单不存在"),
    ORDER_STATUS_ERROR(3002, "订单状态异常"),

    // 秒杀模块
    SECKILL_NOT_START(4001, "秒杀未开始"),
    SECKILL_ENDED(4002, "秒杀已结束"),
    SECKILL_REPEAT(4003, "请勿重复下单"),
    SECKILL_STOCK_EMPTY(4004, "库存已被抢光"),
    SECKILL_RATE_LIMIT(4005, "请求过于频繁，请稍后再试");

    private final int code;
    private final String msg;

    ResultCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
