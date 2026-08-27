package com.rechang.api.client;

import java.util.Map;

/**
 * 实名认证 OCR / 人脸核验外部能力抽象。dev 环境由 {@link com.rechang.api.mock.OcrMock} 提供，
 * 其余环境为 fail-fast 占位实现。
 */
public interface OcrClient {

    Map<String, String> recognizeIdCard(String frontUrl, String backUrl);

    boolean verifyFace(String faceImageUrl, String idCardFrontUrl);
}
