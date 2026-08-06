package com.ecommerce.user.config;

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
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 鉴权过滤器
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final RedisTemplate<String, Object> redisTemplate;

    /** 无需鉴权的完整路径（与 SecurityConfig.permitAll 保持一致；精确匹配，禁止 substring） */
    private static final String[] WHITE_LIST = {
            "/api/user/login",
            "/api/user/register",
            "/api/user/refresh"
    };

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String white : WHITE_LIST) {
            if (path.equals(white)) {
                return true; // 放行
            }
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

        // 解析 Token（不依赖 Redis，JWT 本地可验证）
        Long userId;
        try {
            userId = JwtUtil.getUserId(token);
        } catch (Exception e) {
            response.setStatus(401);
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"Token无效或已过期\",\"data\":null}");
            return;
        }

        // 检查黑名单（Redis 不可用时降级放行）
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey("token:blacklist:" + token))) {
                response.setStatus(401);
                response.setContentType("application/json;charset=utf-8");
                response.getWriter().write("{\"code\":401,\"msg\":\"Token已失效\",\"data\":null}");
                return;
            }
        } catch (Exception ignored) {
            // Redis 不可用，跳过黑名单检查
        }

        request.setAttribute("userId", userId);

        // 填充 SecurityContext：否则 SecurityConfig 的 .authenticated() 永不通过，授权层形同虚设
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userId, null,
                        java.util.Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }
}
