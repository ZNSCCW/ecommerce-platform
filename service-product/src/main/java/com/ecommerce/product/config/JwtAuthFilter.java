package com.ecommerce.product.config;

import com.ecommerce.common.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 商品服务 JWT 鉴权过滤器
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // 公开接口放行（商品浏览、搜索、分类查询）
        if (isPublicPath(path, method)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 从 Header 获取 Token
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.setStatus(401);
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"未登录或Token已过期\",\"data\":null}");
            return;
        }

        String token = authHeader.replace("Bearer ", "");

        try {
            // 检查黑名单
            if (Boolean.TRUE.equals(redisTemplate.hasKey("token:blacklist:" + token))) {
                response.setStatus(401);
                response.getWriter().write("{\"code\":401,\"msg\":\"Token已失效\",\"data\":null}");
                return;
            }

            // 解析 Token
            Long userId = JwtUtil.getUserId(token);
            request.setAttribute("userId", userId);

            filterChain.doFilter(request, response);
        } catch (Exception e) {
            response.setStatus(401);
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"Token无效或已过期\",\"data\":null}");
        }
    }

    private boolean isPublicPath(String path, String method) {
        // 商品浏览
        if (path.contains("/api/product/spu") && "GET".equalsIgnoreCase(method)) {
            return true;
        }
        // 搜索
        if (path.contains("/api/product/search")) {
            // rebuild-index 是管理端接口需要鉴权
            if (path.contains("rebuild-index")) {
                return false;
            }
            return true;
        }
        // 分类
        if (path.contains("/api/product/category") && "GET".equalsIgnoreCase(method)) {
            return true;
        }
        // SKU 查看
        if (path.contains("/api/product/sku") && "GET".equalsIgnoreCase(method)) {
            return true;
        }
        return false;
    }
}
