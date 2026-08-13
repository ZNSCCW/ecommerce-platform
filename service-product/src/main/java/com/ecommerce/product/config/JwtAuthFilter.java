package com.ecommerce.product.config;

import com.ecommerce.common.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
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
     * 无需鉴权的路径（匹配 Gateway 和直连两种场景）。
     * 精确匹配 + AntPathMatcher，禁止 substring 匹配（/spu 片段会误放行 /spuAdmin 等非法路径）。
     */
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        // 内部接口（服务间 Feign 调用）：依赖内网隔离而非 JWT，一律放行
        // 覆盖 Gateway 前缀（/api/product/internal/...）与直连（/internal/...）两种形态
        if (path.startsWith("/internal/") || path.startsWith("/api/product/internal/")) {
            return true;
        }
        // 浏览类 GET 放行（spu 详情/列表、category、sku 查询）。
        // search 的 GET（如 rebuild-index 管理接口）不在 isPublicGetPath 内，默认要求鉴权。
        if ("GET".equalsIgnoreCase(method)) {
            return isPublicGetPath(path);
        }
        // POST 仅放行搜索（商品创建/更新/删除等写操作必须携带 Token）
        if ("POST".equalsIgnoreCase(method) && (MATCHER.match("/api/product/search/**", path)
                || MATCHER.match("/search/**", path))) {
            return true;
        }
        return false;
    }

    /** 公开浏览路径：同时覆盖 Gateway 前缀（/api/product/xxx）与直连（/xxx）两种场景 */
    private boolean isPublicGetPath(String path) {
        return MATCHER.match("/api/product/spu/**", path) || MATCHER.match("/spu/**", path)
                || MATCHER.match("/api/product/category/**", path) || MATCHER.match("/category/**", path)
                || MATCHER.match("/api/product/sku/**", path) || MATCHER.match("/sku/**", path);
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

            // 填充 SecurityContext：否则 SecurityConfig 的 .authenticated() 永不通过，授权层形同虚设
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null,
                            java.util.Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            filterChain.doFilter(request, response);
        } catch (Exception e) {
            response.setStatus(401);
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"Token无效或已过期\",\"data\":null}");
        }
    }
}
