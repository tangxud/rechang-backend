package com.rechang.api.mock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class WechatLoginMock {

    public Map<String, Object> code2session(String code) {
        log.info("[MOCK] WeChat code2session: code={}", code);

        Map<String, Object> result = new HashMap<>();
        result.put("openid", "mock_openid_" + Math.abs(code.hashCode()));
        result.put("session_key", "mock_session_key");
        result.put("unionid", "");
        return result;
    }

    public String decryptPhone(String encryptedData, String iv) {
        log.info("[MOCK] WeChat decryptPhone (returning fixed phone)");
        return "13888888888";
    }
}
