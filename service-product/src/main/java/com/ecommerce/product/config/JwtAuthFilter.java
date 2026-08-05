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

    /**
     * 无需鉴权的路径片段（匹配 Gateway 和直连两种场景）
     */
    private static final String[] PUBLIC_PATH_FRAGMENTS = {
            "/spu",   // GET /api/product/spu/{id} 或 GET /spu/{id}
            "/search", // POST /api/product/search 或 POST /search
            "/category", // GET /api/product/category 或 GET /category
            "/sku"    // GET /api/product/sku 或 GET /sku
    };

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        // 内部接口（服务间 Feign 调用）：依赖内网隔离而非 JWT，一律放行
        if (path.contains("/internal/")) {
            return true;
        }
        // 浏览类 GET 放行（spu 详情/列表、category、sku 查询）
        if ("GET".equalsIgnoreCase(method)) {
            for (String fragment : PUBLIC_PATH_FRAGMENTS) {
                if (path.contains(fragment)) {
                    // rebuild-index 例外：重建索引属于管理操作，需要鉴权
                    if (path.contains("rebuild-index")) {
                        return false;
                    }
                    return true;
                }
            }
            return false;
        }
        // POST 仅放行搜索（商品创建/更新/删除等写操作必须携带 Token）
        if ("POST".equalsIgnoreCase(method) && path.contains("/search")) {
            return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

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
