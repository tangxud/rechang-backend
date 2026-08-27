package com.rechang.api.mock;

import com.rechang.api.client.OcrClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 实名认证 OCR / 人脸核验 Mock：仅 dev 环境注册，返回固定假数据。
 */
@Slf4j
@Component
@Profile("dev")
public class OcrMock implements OcrClient {

    @Override
    public Map<String, String> recognizeIdCard(String frontUrl, String backUrl) {
        log.info("[MOCK] OCR recognize ID card: front={}, back={}", frontUrl, backUrl);

        Map<String, String> result = new HashMap<>();
        result.put("name", "张三明");
        result.put("id_card_no", "330102199001011234");
        return result;
    }

    @Override
    public boolean verifyFace(String faceImageUrl, String idCardFrontUrl) {
        log.info("[MOCK] Face verification (auto pass)");
        return true;
    }
}
