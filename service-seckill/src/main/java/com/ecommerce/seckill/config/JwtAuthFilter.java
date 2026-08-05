package com.ecommerce.seckill.config;

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
 * 秒杀服务 JWT 鉴权过滤器
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

        // 公开接口放行
        if (isPublicPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            writeUnauthorized(response, "未登录或Token已过期");
            return;
        }

        String token = authHeader.replace("Bearer ", "");

        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey("token:blacklist:" + token))) {
                writeUnauthorized(response, "Token已失效");
                return;
            }
            Long userId = JwtUtil.getUserId(token);
            request.setAttribute("userId", userId);
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            writeUnauthorized(response, "Token无效或已过期");
        }
    }

    private boolean isPublicPath(String path) {
        return path.contains("/api/seckill/activities") ||
               path.contains("/api/seckill/stock/");
    }

    private void writeUnauthorized(HttpServletResponse response, String msg) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write("{\"code\":401,\"msg\":\"" + msg + "\",\"data\":null}");
    }
}
