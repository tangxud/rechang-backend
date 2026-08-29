package com.rechang.api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 微信 session_key 服务端持久化：登录时保存，绑定手机号解密 getPhoneNumber 密文时取用。
 * 微信不保证 session_key 长期有效（用户重新登录/长期未使用即失效），TTL 取 72h，
 * 过期后由调用方引导用户重新登录。
 */
@Service
@RequiredArgsConstructor
public class WechatSessionKeyStore {

    private static final String KEY_PREFIX = "wechat:session-key:";
    private static final Duration TTL = Duration.ofHours(72);

    private final StringRedisTemplate redisTemplate;

    public void save(Long userId, String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(KEY_PREFIX + userId, sessionKey, TTL);
    }

    /** 取不到（未登录过/已过期）返回 null，由调用方决定降级行为 */
    public String load(Long userId) {
        return redisTemplate.opsForValue().get(KEY_PREFIX + userId);
    }
}
