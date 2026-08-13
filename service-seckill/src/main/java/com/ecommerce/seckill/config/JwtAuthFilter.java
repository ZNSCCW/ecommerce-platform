package com.ecommerce.seckill.config;

import com.ecommerce.common.JwtUtil;
import io.jsonwebtoken.Claims;
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
import java.util.ArrayList;
import java.util.List;

/**
 * 秒杀服务 JWT 鉴权过滤器
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 与 SecurityConfig.permitAll 保持一致：Ant 精确匹配公开端点，禁止 substring 匹配
        String path = request.getRequestURI();
        org.springframework.util.AntPathMatcher m = new org.springframework.util.AntPathMatcher();
        return m.match("/api/seckill/activities/**", path)
                || m.match("/api/seckill/stock/**", path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

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
            // 角色一并写入，供 warm-up 等管理接口做 ADMIN 校验
            Claims claims = JwtUtil.parseToken(token);
            String role = claims.get("role", String.class);
            request.setAttribute("role", role);

            // 填充 SecurityContext：否则 SecurityConfig 的 .authenticated() 永不通过，授权层形同虚设
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            if ("ADMIN".equals(role) || "SUPER_ADMIN".equals(role)) {
                authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
            }
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);

            filterChain.doFilter(request, response);
        } catch (Exception e) {
            writeUnauthorized(response, "Token无效或已过期");
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String msg) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write("{\"code\":401,\"msg\":\"" + msg + "\",\"data\":null}");
    }
}
