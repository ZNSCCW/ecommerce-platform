package com.ecommerce.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类（双 Token）
 */
public class JwtUtil {

    /** 项目中请替换为更复杂的密钥，勿硬编码 */
    private static final String SECRET = "EcommercePlatformSecretKeyForJWT2024MustBe256BitsLong!";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    /** AccessToken 过期时间：30 分钟 */
    private static final long ACCESS_EXPIRE = 30 * 60 * 1000L;
    /** RefreshToken 过期时间：7 天 */
    private static final long REFRESH_EXPIRE = 7 * 24 * 60 * 60 * 1000L;

    /**
     * 生成 AccessToken
     * @param userId 用户ID
     * @param role   角色标识
     */
    public static String generateAccessToken(Long userId, String role) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .claim("type", "access")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ACCESS_EXPIRE))
                .signWith(KEY)
                .compact();
    }

    /**
     * 生成 RefreshToken
     */
    public static String generateRefreshToken(Long userId) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("type", "refresh")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + REFRESH_EXPIRE))
                .signWith(KEY)
                .compact();
    }

    /**
     * 解析 Token
     */
    public static Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(KEY)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 校验 Token 是否过期
     */
    public static boolean isTokenExpired(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 从 Token 中提取用户ID
     */
    public static Long getUserId(String token) {
        Claims claims = parseToken(token);
        return Long.parseLong(claims.getSubject());
    }
}
