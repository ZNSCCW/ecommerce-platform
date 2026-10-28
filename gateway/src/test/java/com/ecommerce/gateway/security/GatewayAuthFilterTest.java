package com.ecommerce.gateway.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 网关鉴权过滤器单元测试（不加载 Spring 上下文）。
 * 覆盖：白名单、缺 token、非法 token、refresh 冒充 access、黑名单、userId 透传与伪造头清理。
 */
class GatewayAuthFilterTest {

    /** 与 JwtVerifier / common JwtUtil 使用同一份开发默认密钥 */
    private static final String SECRET = "EcommercePlatformSecretKeyForJWT2024MustBe256BitsLong!";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private ReactiveStringRedisTemplate redisTemplate;
    private GatewayAuthProperties properties;
    private GatewayAuthFilter filter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        properties = new GatewayAuthProperties();
        properties.setWhitelist(List.of(
                "POST /api/user/login",
                "POST /api/user/register",
                "GET /api/product/**",
                "GET /api/seckill/stock/**"
        ));
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(Boolean.FALSE));
        filter = new GatewayAuthFilter(properties, redisTemplate);
    }

    @Test
    void whitelistedPathShouldPassWithoutToken() {
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        Mono<Void> result = filter.filter(
                MockServerWebExchange.from(MockServerHttpRequest.post("/api/user/login")), chain(passed));

        result.block();
        assertThat(passed.get()).isNotNull();
        assertThat(passed.get().getResponse().getStatusCode()).isNull();
    }

    @Test
    void corsPreflightShouldPassWithoutToken() {
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        filter.filter(MockServerWebExchange.from(
                MockServerHttpRequest.options("/api/seckill")), chain(passed)).block();

        assertThat(passed.get()).isNotNull();
    }

    @Test
    void protectedPathWithoutTokenShouldReturn401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/seckill"));

        filter.filter(exchange, chain(new AtomicReference<>())).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void invalidTokenShouldReturn401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/seckill").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.token"));

        filter.filter(exchange, chain(new AtomicReference<>())).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshTokenShouldNotBeAcceptedAsAccessToken() {
        String refresh = token(1L, "refresh", 60_000);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/seckill").header(HttpHeaders.AUTHORIZATION, "Bearer " + refresh));

        filter.filter(exchange, chain(new AtomicReference<>())).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void blacklistedTokenShouldReturn401() {
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(Boolean.TRUE));
        String access = token(7L, "access", 60_000);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/seckill").header(HttpHeaders.AUTHORIZATION, "Bearer " + access));

        filter.filter(exchange, chain(new AtomicReference<>())).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void validTokenShouldPassAndOverwriteSpoofedUserIdHeader() {
        String access = token(42L, "access", 60_000);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/seckill")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
                        .header(GatewayAuthFilter.USER_ID_HEADER, "999"));   // 客户端伪造

        filter.filter(exchange, chain(passed)).block();

        assertThat(passed.get()).isNotNull();
        assertThat(passed.get().getRequest().getHeaders().getFirst(GatewayAuthFilter.USER_ID_HEADER))
                .isEqualTo("42");
    }

    private GatewayFilterChain chain(AtomicReference<ServerWebExchange> holder) {
        return exchange -> {
            holder.set(exchange);
            return Mono.empty();
        };
    }

    private String token(Long userId, String type, long ttlMillis) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("type", type)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ttlMillis))
                .signWith(KEY)
                .compact();
    }
}
