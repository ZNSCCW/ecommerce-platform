package com.ecommerce.order.config;

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

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.contains("/pay") || path.contains("/pay-by-order-no");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.setStatus(401);
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"未登录\",\"data\":null}");
            return;
        }
        try {
            String token = authHeader.replace("Bearer ", "");
            if (Boolean.TRUE.equals(redisTemplate.hasKey("token:blacklist:" + token))) {
                response.setStatus(401);
                response.getWriter().write("{\"code\":401,\"msg\":\"Token已失效\",\"data\":null}");
                return;
            }
            request.setAttribute("userId", JwtUtil.getUserId(token));
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            response.setStatus(401);
            response.getWriter().write("{\"code\":401,\"msg\":\"Token无效\",\"data\":null}");
        }
    }
}
