package com.rechang.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class JwtUtils {

    /**
     * 内置默认密钥仅供本地开发与单元测试兜底；
     * 各环境实际密钥经 application-{profile}.yml 的 jwt.secret 由 AppSecretConfig 注入，
     * 生产环境无默认可用（缺失即启动失败）。密钥规范见 docs/design/environment_config.md。
     */
    private static final String DEFAULT_SECRET = "rechang-secret-key-for-jwt-token-2026-must-be-at-least-32-chars";
    private static final long EXPIRATION_MS = 7 * 24 * 60 * 60 * 1000L;

    private static volatile SecretKey key = Keys.hmacShaKeyFor(DEFAULT_SECRET.getBytes(StandardCharsets.UTF_8));

    public static void initSecret(String secret) {
        if (secret != null && !secret.isBlank()) {
            if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException("jwt.secret 至少需要 32 字节（HS256 要求）");
            }
            key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static String generateToken(Long userId, String openid) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("openid", openid);
        return Jwts.builder()
                .claims(claims)
                .subject(String.valueOf(userId))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION_MS))
                .signWith(key)
                .compact();
    }

    public static Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public static Long getUserId(String token) {
        Claims claims = parseToken(token);
        Object userId = claims.get("userId");
        if (userId instanceof Integer) {
            return ((Integer) userId).longValue();
        }
        return (Long) userId;
    }

    public static long getExpirationSeconds() {
        return EXPIRATION_MS / 1000;
    }
}
