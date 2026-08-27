package com.rechang.api.client;

import com.rechang.common.exception.BusinessException;
import com.rechang.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 非 dev 环境占位实现：接入阿里云 OCR / 腾讯云慧眼等实名核验服务后替换。
 */
@Slf4j
@Component
@Profile("!dev")
public class OcrStub implements OcrClient {

    @Override
    public Map<String, String> recognizeIdCard(String frontUrl, String backUrl) {
        throw new BusinessException(ResultCode.INTERNAL_ERROR, "身份证 OCR 未接入生产实现（当前环境非 dev）");
    }

    @Override
    public boolean verifyFace(String faceImageUrl, String idCardFrontUrl) {
        throw new BusinessException(ResultCode.INTERNAL_ERROR, "人脸核验未接入生产实现（当前环境非 dev）");
    }
}
