package com.rechang.api.client;

import java.util.Map;

/**
 * 微信登录外部能力抽象。dev 环境由 {@link com.rechang.api.mock.WechatLoginMock} 提供固定数据，
 * 其余环境为 fail-fast 占位实现；接入微信开放平台 SDK 后补充真实实现并按 Profile 注册。
 */
public interface WechatLoginClient {

    Map<String, Object> code2session(String code);

    String decryptPhone(String encryptedData, String iv);
}
