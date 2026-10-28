package com.ecommerce.gateway.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关统一鉴权（GlobalFilter）。
 *
 * <p>职责：白名单直放；其余请求验签、查 Redis 黑名单、把 userId 透传给下游。
 * 各服务自己的 {@code JwtAuthFilter} 保留不动，形成「网关入口认证 + 服务侧防御性校验」的双保险：
 * 网关负责收敛入口、集中维护白名单；服务侧负责防止内网直连绕过网关。
 *
 * <p>两个实现细节：
 * <ul>
 *   <li>黑名单查询走 {@link ReactiveStringRedisTemplate}，不在事件循环线程上做阻塞 IO；</li>
 *   <li>透传前先删掉客户端自带的 {@code X-User-Id}，防止伪造身份头。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayAuthFilter implements GlobalFilter, Ordered {

    /** 网关在通过鉴权后写入下游的 userId 头 */
    public static final String USER_ID_HEADER = "X-User-Id";

    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final String BEARER = "Bearer ";

    private final GatewayAuthProperties properties;
    private final ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        String method = request.getMethod() == null ? "" : request.getMethod().name();

        // CORS 预检不带 Authorization，必须放行，否则浏览器侧跨域直接失败
        if ("OPTIONS".equalsIgnoreCase(method) || isWhitelisted(method, path)) {
            return chain.filter(exchange);
        }

        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER)) {
            return unauthorized(exchange, "未登录");
        }

        String token = authorization.substring(BEARER.length()).trim();
        final Long userId;
        try {
            userId = JwtVerifier.verifyAccessToken(token);
        } catch (Exception e) {
            log.debug("网关验签失败: path={}, reason={}", path, e.getMessage());
            return unauthorized(exchange, "Token无效");
        }

        return redisTemplate.hasKey(properties.getBlacklistPrefix() + token)
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(blocked -> {
                    if (Boolean.TRUE.equals(blocked)) {
                        return unauthorized(exchange, "Token已失效");
                    }
                    return chain.filter(withUserId(exchange, userId));
                });
    }

    /** 透传 userId：先删客户端伪造的同名头，再写入验签结果 */
    private ServerWebExchange withUserId(ServerWebExchange exchange, Long userId) {
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(USER_ID_HEADER);
                    headers.set(USER_ID_HEADER, String.valueOf(userId));
                })
                .build();
        return exchange.mutate().request(mutated).build();
    }

    private boolean isWhitelisted(String method, String path) {
        for (String rule : properties.getWhitelist()) {
            String[] parts = rule.trim().split("\\s+", 2);
            if (parts.length != 2) {
                continue;
            }
            if (!"*".equals(parts[0]) && !parts[0].equalsIgnoreCase(method)) {
                continue;
            }
            if (MATCHER.match(parts[1], path)) {
                return true;
            }
        }
        return false;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"code\":401,\"msg\":\"" + message + "\",\"data\":null}")
                .getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        // 先于路由类过滤器执行
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
