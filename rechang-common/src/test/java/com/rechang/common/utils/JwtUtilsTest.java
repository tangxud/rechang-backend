package com.rechang.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtUtils 生成/解析 round-trip 与异常分支。
 * 注意 SECRET 是类内静态状态，每个用例自备独立密钥，避免用例间耦合。
 */
class JwtUtilsTest {

    private static final String SECRET_A = "test-secret-a-0123456789abcdef0123456789abcdef";
    private static final String SECRET_B = "test-secret-b-fedcba98765432100123456789abcdef";

    @BeforeEach
    void resetToOwnSecret() {
        JwtUtils.initSecret(SECRET_A);
    }

    @Test
    @DisplayName("generateToken → parseToken round-trip，claims 完整")
    void roundTrip() {
        String token = JwtUtils.generateToken(42L, "openid_42");
        Claims claims = JwtUtils.parseToken(token);
        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(JwtUtils.getUserId(token)).isEqualTo(42L);
        assertThat(claims.get("openid", String.class)).isEqualTo("openid_42");
    }

    @Test
    @DisplayName("跨签名密钥解析失败（key 污染检测）")
    void parseFailsWithDifferentKey() {
        String tokenA = JwtUtils.generateToken(1L, "o1");
        JwtUtils.initSecret(SECRET_B);
        assertThatThrownBy(() -> JwtUtils.parseToken(tokenA)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("篡改 payload 解析失败")
    void tamperedTokenRejected() {
        String token = JwtUtils.generateToken(1L, "o1");
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        String tampered = parts[0] + ". altered" + "." + parts[2];
        assertThatThrownBy(() -> JwtUtils.parseToken(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("过期 token 抛 ExpiredJwtException")
    void expiredTokenRejected() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET_A.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", 1L);
        claims.put("openid", "o1");
        String expired = Jwts.builder()
                .claims(claims)
                .subject("1")
                .issuedAt(new Date(System.currentTimeMillis() - 60_000))
                .expiration(new Date(System.currentTimeMillis() - 30_000))
                .signWith(key)
                .compact();
        assertThatThrownBy(() -> JwtUtils.parseToken(expired)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("历史兼容：userId 序列化为 Integer 时可正确转 Long")
    void integerUserIdCompat() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET_A.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", 7);   // Integer
        claims.put("openid", "o7");
        String legacy = Jwts.builder().claims(claims).subject("7")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key).compact();
        assertThat(JwtUtils.getUserId(legacy)).isEqualTo(7L);
    }

    @Test
    @DisplayName("initSecret: 短于 32 字节拒绝切换，原密钥继续有效")
    void initSecretRejectsShortKey() {
        String token = JwtUtils.generateToken(1L, "o1");
        assertThatThrownBy(() -> JwtUtils.initSecret("short")).isInstanceOf(IllegalArgumentException.class);
        assertThat(JwtUtils.parseToken(token)).isNotNull();
    }

    @Test
    @DisplayName("initSecret: 空白值忽略，不改变当前密钥")
    void initSecretIgnoresBlank() {
        String token = JwtUtils.generateToken(1L, "o1");
        JwtUtils.initSecret(" ");
        JwtUtils.initSecret(null);
        assertThat(JwtUtils.parseToken(token)).isNotNull();
    }

    @Test
    @DisplayName("getExpirationSeconds 固定 7 天")
    void expirationSeconds() {
        assertThat(JwtUtils.getExpirationSeconds()).isEqualTo(7 * 24 * 3600L);
    }
}
