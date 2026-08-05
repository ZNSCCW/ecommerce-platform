package com.ecommerce.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;

/**
 * 接口日志切面 - 记录请求参数、耗时、响应
 */
@Slf4j
@Aspect
@Component
public class ApiLogAspect {

    @Around("execution(* com.ecommerce..controller..*.*(..))")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 获取请求信息
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attrs != null ? attrs.getRequest() : null;

        String method = request != null ? request.getMethod() : "";
        String uri = request != null ? request.getRequestURI() : "";
        String className = joinPoint.getSignature().getDeclaringTypeName();
        String methodName = joinPoint.getSignature().getName();
        Object[] args = joinPoint.getArgs();

        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            long elapsed = System.currentTimeMillis() - start;
            log.info("[API] {} {} → {}.{}({}) | 耗时: {}ms", method, uri, className, methodName,
                    args.length > 0 ? Arrays.toString(args) : "", elapsed);
            return result;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[API] {} {} → {}.{} | 耗时: {}ms | 异常: {}", method, uri, className, methodName, elapsed, e.getMessage());
            throw e;
        }
    }
}
