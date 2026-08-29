package com.rechang.api.mock;

import com.rechang.api.client.WechatLoginClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 微信登录 Mock：仅 dev 环境注册，返回固定假数据，便于脱离微信开发者工具联调。
 */
@Slf4j
@Component
@Profile("dev")
public class WechatLoginMock implements WechatLoginClient {

    @Override
    public Map<String, Object> code2session(String code) {
        log.info("[MOCK] WeChat code2session: code={}", code);

        Map<String, Object> result = new HashMap<>();
        result.put("openid", "mock_openid_" + Math.abs(code.hashCode()));
        result.put("session_key", "mock_session_key");
        result.put("unionid", "");
        return result;
    }

    @Override
    public String decryptPhone(String sessionKey, String encryptedData, String iv) {
        log.info("[MOCK] WeChat decryptPhone (returning fixed phone)");
        return "13888888888";
    }
}
