package com.rechang.api.client;

import com.rechang.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 真实微信客户端单测：HTTP 层经 MockRestServiceServer 模拟微信响应，
 * 解密链路用测试内生成的 AES 密文回环验证（个人主体小程序拿不到 getPhoneNumber 真实数据）。
 */
class WechatLoginClientRealTest {

    private static final String APPID = "wx1234567890abcdef";
    private static final String SECRET = "unit-test-secret";
    private static final String BASE_URL = "/sns/jscode2session"
            + "?appid=" + APPID + "&secret=" + SECRET + "&js_code=wx-code&grant_type=authorization_code";

    private MockRestServiceServer server;
    private WechatLoginClientReal client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new WechatLoginClientReal(APPID, SECRET, builder.build());
    }

    /* ================= code2session ================= */

    @Test
    @DisplayName("code2session 成功：返回 openid/session_key/unionid")
    void code2sessionSuccess() {
        server.expect(requestTo(URI.create(BASE_URL))).andRespond(withSuccess(
                "{\"openid\":\"o-1\",\"session_key\":\"sk-1\",\"unionid\":\"u-1\"}", MediaType.APPLICATION_JSON));

        Map<String, Object> session = client.code2session("wx-code");

        assertThat(session.get("openid")).isEqualTo("o-1");
        assertThat(session.get("session_key")).isEqualTo("sk-1");
        assertThat(session.get("unionid")).isEqualTo("u-1");
        server.verify();
    }

    @Test
    @DisplayName("code2session 无 unionid 时以空串兜底")
    void code2sessionWithoutUnionid() {
        server.expect(requestTo(URI.create(BASE_URL))).andRespond(withSuccess(
                "{\"openid\":\"o-1\",\"session_key\":\"sk-1\"}", MediaType.APPLICATION_JSON));

        assertThat(client.code2session("wx-code").get("unionid")).isEqualTo("");
    }

    @Test
    @DisplayName("code2session 业务错误码（如 40029 code 无效）→ WECHAT_AUTH_FAILED")
    void code2sessionWxError() {
        server.expect(requestTo(URI.create(BASE_URL))).andRespond(withSuccess(
                "{\"errcode\":40029,\"errmsg\":\"invalid code\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.code2session("wx-code"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1035))
                .hasMessageContaining("40029")
                .hasMessageContaining("invalid code");
    }

    @Test
    @DisplayName("code2session 响应缺 openid → WECHAT_AUTH_FAILED")
    void code2sessionMissingOpenid() {
        server.expect(requestTo(URI.create(BASE_URL))).andRespond(withSuccess(
                "{\"session_key\":\"sk-1\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.code2session("wx-code"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1035));
    }

    @Test
    @DisplayName("微信侧 5xx → WECHAT_AUTH_FAILED（不裸抛 RestClientException）")
    void code2sessionServerError() {
        server.expect(requestTo(URI.create(BASE_URL))).andRespond(withServerError());

        assertThatThrownBy(() -> client.code2session("wx-code"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1035));
    }

    /* ================= decryptPhone ================= */

    /** 用与实现相同的 AES-128-CBC/PKCS5 流程反向生成微信侧密文 */
    private static String[] encryptForWechat(byte[] sessionKey, String plainJson) throws Exception {
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sessionKey, "AES"), new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(plainJson.getBytes(StandardCharsets.UTF_8));
        Base64.Encoder b64 = Base64.getEncoder();
        return new String[]{b64.encodeToString(sessionKey), b64.encodeToString(encrypted), b64.encodeToString(iv)};
    }

    @Test
    @DisplayName("解密 getPhoneNumber 密文：返回手机号")
    void decryptPhoneSuccess() throws Exception {
        byte[] sessionKey = new byte[16];
        new SecureRandom().nextBytes(sessionKey);
        String[] parts = encryptForWechat(sessionKey,
                "{\"phoneNumber\":\"13800001111\",\"purePhoneNumber\":\"13800001111\",\"countryCode\":\"86\","
                        + "\"watermark\":{\"timestamp\":1690000000,\"appid\":\"" + APPID + "\"}}");

        String phone = client.decryptPhone(parts[0], parts[1], parts[2]);

        assertThat(phone).isEqualTo("13800001111");
    }

    @Test
    @DisplayName("水印 appid 不匹配 → WECHAT_DECRYPT_FAILED")
    void decryptPhoneWatermarkMismatch() throws Exception {
        byte[] sessionKey = new byte[16];
        new SecureRandom().nextBytes(sessionKey);
        String[] parts = encryptForWechat(sessionKey,
                "{\"phoneNumber\":\"13800001111\",\"watermark\":{\"appid\":\"wx-other-app\"}}");

        assertThatThrownBy(() -> client.decryptPhone(parts[0], parts[1], parts[2]))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1036));
    }

    @Test
    @DisplayName("密文被篡改/密钥不符 → WECHAT_DECRYPT_FAILED")
    void decryptPhoneTampered() throws Exception {
        byte[] sessionKey = new byte[16];
        new SecureRandom().nextBytes(sessionKey);
        String[] parts = encryptForWechat(sessionKey,
                "{\"phoneNumber\":\"13800001111\",\"watermark\":{\"appid\":\"" + APPID + "\"}}");
        byte[] tampered = Base64.getDecoder().decode(parts[1]);
        tampered[tampered.length - 1] ^= 0x01;

        assertThatThrownBy(() -> client.decryptPhone(parts[0],
                Base64.getEncoder().encodeToString(tampered), parts[2]))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1036));
    }

    @Test
    @DisplayName("session_key 缺失（未登录/已过期）→ UNAUTHORIZED 引导重新登录")
    void decryptPhoneWithoutSessionKey() {
        assertThatThrownBy(() -> client.decryptPhone(null, "enc", "iv"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(401));
    }

    /* ================= 启动 fail-fast ================= */

    @Test
    @DisplayName("test/prod 凭据缺失：构造即抛 IllegalStateException（启动 fail-fast）")
    void constructorFailsFastOnBlankCredentials() {
        assertThatThrownBy(() -> new WechatLoginClientReal("", SECRET, RestClient.builder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WECHAT_APPID");
        assertThatThrownBy(() -> new WechatLoginClientReal(APPID, " ", RestClient.builder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WECHAT_SECRET");
    }
}
