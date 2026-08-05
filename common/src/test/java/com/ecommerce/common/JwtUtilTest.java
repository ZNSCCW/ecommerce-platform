package com.ecommerce.common;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JwtUtil 单元测试：双 Token 的生成/解析/过期/防伪。
 */
class JwtUtilTest {

    @Test
    void generateAndParseAccessToken_shouldRoundTrip() {
        String token = JwtUtil.generateAccessToken(42L, "ADMIN");
        Claims claims = JwtUtil.parseToken(token);

        assertEquals("42", claims.getSubject());
        assertEquals("ADMIN", claims.get("role", String.class));
        assertEquals("access", claims.get("type", String.class));
        assertEquals(42L, JwtUtil.getUserId(token));
    }

    @Test
    void generateRefreshToken_shouldBeTypeRefresh() {
        String token = JwtUtil.generateRefreshToken(7L);
        assertEquals("refresh", JwtUtil.parseToken(token).get("type", String.class));
    }

    @Test
    void freshToken_shouldNotBeExpired() {
        String token = JwtUtil.generateAccessToken(1L, "USER");
        assertFalse(JwtUtil.isTokenExpired(token));
    }

    @Test
    void malformedToken_shouldBeTreatedAsExpiredOrThrow() {
        // 非法 token：isTokenExpired 返回 true（内部 catch），parse 抛异常
        assertTrue(JwtUtil.isTokenExpired("not.a.jwt"));
        assertThrows(Exception.class, () -> JwtUtil.parseToken("not.a.jwt"));
    }
}
