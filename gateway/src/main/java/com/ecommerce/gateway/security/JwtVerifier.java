package com.ecommerce.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * 网关侧最小 JWT 验签实现。
 *
 * <p>为什么不直接复用 common 模块的 {@code JwtUtil}：common 是
 * {@code spring-boot-starter-web} + MyBatis-Plus 的 Servlet 聚合模块，
 * 而网关跑在 WebFlux 反应式栈上。一旦引入 common，Servlet 的 spring-webmvc
 * 会与 Gateway 的 WebFlux 冲突（启动即报 Spring MVC found on classpath）。
 * 因此这里只保留验签必需的代码，密钥来源与 common/JwtUtil 完全一致：
 * {@code JWT_SECRET} 环境变量 &gt; {@code jwt.secret} 系统属性 &gt; 开发默认值。
 *
 * <p>除了验签，这里还额外校验 {@code type=access}：JWT 里的 refresh token
 * 有效期 7 天，如果允许它当 access token 用，等于把会话有效期放大了 14 倍。
 */
public final class JwtVerifier {

    private static final String SECRET = System.getenv().getOrDefault("JWT_SECRET",
            System.getProperty("jwt.secret",
                    "EcommercePlatformSecretKeyForJWT2024MustBe256BitsLong!"));

    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private JwtVerifier() {
    }

    /**
     * 验签 + 校验过期 + 校验 token 类型，返回 userId。
     *
     * @throws IllegalArgumentException token 非法 / 过期 / 不是 access token
     */
    public static Long verifyAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(KEY)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Date expiration = claims.getExpiration();
        if (expiration == null || expiration.before(new Date())) {
            throw new IllegalArgumentException("token expired");
        }
        if (!"access".equals(claims.get("type", String.class))) {
            throw new IllegalArgumentException("not an access token");
        }
        return Long.parseLong(claims.getSubject());
    }
}
