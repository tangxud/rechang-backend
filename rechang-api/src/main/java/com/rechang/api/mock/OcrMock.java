package com.rechang.api.mock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class OcrMock {

    public Map<String, String> recognizeIdCard(String frontUrl, String backUrl) {
        log.info("[MOCK] OCR recognize ID card: front={}, back={}", frontUrl, backUrl);

        Map<String, String> result = new HashMap<>();
        result.put("name", "张三明");
        result.put("id_card_no", "330102199001011234");
        return result;
    }

    public boolean verifyFace(String faceImageUrl, String idCardFrontUrl) {
        log.info("[MOCK] Face verification (auto pass)");
        return true;
    }
}
