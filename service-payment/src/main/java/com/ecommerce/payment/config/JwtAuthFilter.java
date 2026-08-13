package com.ecommerce.payment.config;

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

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 精确匹配支付宝回调路径；禁止 substring 匹配，防止 /xxx/notify/yyy 类路径被误放行
        return "/api/payment/notify".equals(request.getRequestURI());
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
            response.getWriter().write("{\"code\":401,\"msg\":\"Token无效\",\"data\":null}");
        }
    }
}
