package com.ecommerce.order.config;

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

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final RedisTemplate<String, Object> redisTemplate;

    /** 与 SecurityConfig.permitAll 保持一致：精确匹配 + AntPathMatcher，禁止 substring（/pay 会误放行 /payXxx） */
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return MATCHER.match("/api/order/**/pay", path)
                || "/api/order/pay-by-order-no".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            writeUnauthorized(response, "未登录");
            return;
        }
        try {
            String token = authHeader.replace("Bearer ", "").trim();
            if (Boolean.TRUE.equals(redisTemplate.hasKey("token:blacklist:" + token))) {
                writeUnauthorized(response, "Token已失效");
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
            writeUnauthorized(response, "Token无效");
        }
    }

    /** 统一写 401 JSON 响应（保证所有 401 路径都有正确的 content-type） */
    private void writeUnauthorized(HttpServletResponse response, String msg) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write("{\"code\":401,\"msg\":\"" + msg + "\",\"data\":null}");
    }
}
