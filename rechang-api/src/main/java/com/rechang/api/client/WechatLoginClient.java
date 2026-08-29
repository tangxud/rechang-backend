package com.rechang.api.client;

import java.util.Map;

/**
 * 微信登录外部能力抽象。dev 环境由 {@link com.rechang.api.mock.WechatLoginMock} 提供固定数据，
 * 其余环境由 {@link WechatLoginClientReal} 调用微信开放接口（凭据见 application.yml wechat 段）。
 */
public interface WechatLoginClient {

    Map<String, Object> code2session(String code);

    /**
     * 解密 getPhoneNumber 回传数据得到手机号。
     *
     * @param sessionKey    登录时 code2session 返回并经 {@code WechatSessionKeyStore} 持久化的会话密钥
     * @param encryptedData 小程序 button open-type=getPhoneNumber 回传的密文（Base64）
     * @param iv            算法偏移量（Base64）
     */
    String decryptPhone(String sessionKey, String encryptedData, String iv);
}
