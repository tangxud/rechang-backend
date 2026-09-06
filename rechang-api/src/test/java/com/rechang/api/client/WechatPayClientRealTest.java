package com.rechang.api.client;

import com.rechang.common.exception.BusinessException;
import com.rechang.api.vo.PayParamsVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 微信支付 v3 真实客户端单测：HTTP 层经 MockRestServiceServer 模拟；
 * RSA 密钥对与 AES-GCM 密文均在测试内生成（本地无商户号，无真实凭据可用）。
 */
class WechatPayClientRealTest {

    private static final String APPID = "wxf9790511db248896";
    private static final String MCHID = "1900000001";
    private static final String API_V3_KEY = "0123456789abcdef0123456789abcdef"; // 32 字节
    private static final String SERIAL = "TEST_SERIAL_NO";

    private KeyPair merchantKey;
    private KeyPair platformKey;
    private MockRestServiceServer server;
    private WechatPayClientReal client;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        merchantKey = kpg.generateKeyPair();
        platformKey = kpg.generateKeyPair();

        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new WechatPayClientReal(APPID, MCHID, API_V3_KEY, SERIAL,
                pem("PRIVATE KEY", merchantKey.getPrivate().getEncoded()),
                pem("PUBLIC KEY", platformKey.getPublic().getEncoded()),
                "https://api.example.com/pay/notify", builder.build());
    }

    private static String pem(String type, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----";
    }

    /* ================= 统一下单 ================= */

    private static final String BASE_URL = "https://api.mch.weixin.qq.com";

    @Test
    @DisplayName("JSAPI 统一下单：请求体含商户单号/金额/openid，鉴权头为 WECHATPAY2-SHA256-RSA2048")
    void createPayParamsJsapi() {
        server.expect(requestTo(BASE_URL + "/v3/pay/transactions/jsapi"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization",
                        new org.hamcrest.BaseMatcher<String>() {
                            @Override public boolean matches(Object o) { return String.valueOf(o).startsWith("WECHATPAY2-SHA256-RSA2048 mchid=\"" + MCHID + "\""); }
                            @Override public void describeTo(org.hamcrest.Description d) { d.appendText("v3 auth header"); }
                        }))
                .andExpect(content().json("""
                        {"appid":"%s","mchid":"%s","out_trade_no":"NO123","notify_url":"https://api.example.com/pay/notify",
                         "amount":{"total":38000,"currency":"CNY"},"payer":{"openid":"o-user"}}""".formatted(APPID, MCHID), false))
                .andRespond(withSuccess("{\"prepay_id\":\"wxABC\"}", MediaType.APPLICATION_JSON));

        var order = new com.rechang.api.entity.OrderEntity();
        order.setOrderNo("NO123");
        order.setTotalAmount(38000);

        PayParamsVO vo = client.createPayParams(order, "o-user");

        assertThat(vo.getPackageStr()).isEqualTo("prepay_id=wxABC");
        assertThat(vo.getSignType()).isEqualTo("RSA");
        assertThat(vo.getTimeStamp()).isNotBlank();
        assertThat(vo.getNonceStr()).isNotBlank();
    }

    @Test
    @DisplayName("拉起收银台 paySign 可被商户公钥验签通过（appId\\nts\\nnonce\\npackage\\n）")
    void paySignVerifiableByMerchantPublicKey() throws Exception {
        server.expect(requestTo(BASE_URL + "/v3/pay/transactions/jsapi"))
                .andRespond(withSuccess("{\"prepay_id\":\"wxABC\"}", MediaType.APPLICATION_JSON));

        var order = new com.rechang.api.entity.OrderEntity();
        order.setOrderNo("NO123");
        order.setTotalAmount(100);
        PayParamsVO vo = client.createPayParams(order, "o-user");

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(merchantKey.getPublic());
        verifier.update(("appId\n" + vo.getTimeStamp() + "\n" + vo.getNonceStr() + "\n" + vo.getPackageStr() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(vo.getPaySign()))).isTrue();
    }

    @Test
    @DisplayName("统一下单响应缺 prepay_id → WECHAT_PAY_FAILED")
    void createPayParamsMissingPrepayId() {
        server.expect(requestTo(BASE_URL + "/v3/pay/transactions/jsapi"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        var order = new com.rechang.api.entity.OrderEntity();
        order.setOrderNo("NO123");
        order.setTotalAmount(100);
        assertThatThrownBy(() -> client.createPayParams(order, "o-user"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1037));
    }

    @Test
    @DisplayName("统一下单 5xx → WECHAT_PAY_FAILED")
    void createPayParamsServerError() {
        server.expect(requestTo(BASE_URL + "/v3/pay/transactions/jsapi")).andRespond(withServerError());

        var order = new com.rechang.api.entity.OrderEntity();
        order.setOrderNo("NO123");
        order.setTotalAmount(100);
        assertThatThrownBy(() -> client.createPayParams(order, "o-user"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1037));
    }

    /* ================= 回调验签 + 解密 ================= */

    /** 测试内模拟微信侧：AES-256-GCM 加密回调资源 + 平台私钥对通知体签名 */
    private String[] buildSignedCallback(String plainJson) throws Exception {
        String nonce = "abcdef123456";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(API_V3_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD("transaction".getBytes(StandardCharsets.UTF_8));
        String ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(plainJson.getBytes(StandardCharsets.UTF_8)));

        String body = "{\"id\":\"evt-1\",\"resource\":{\"ciphertext\":\"" + ciphertext
                + "\",\"nonce\":\"" + nonce + "\",\"associated_data\":\"transaction\"}}";
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String sigNonce = "callback-nonce";

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(platformKey.getPrivate());
        signer.update((timestamp + "\n" + sigNonce + "\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        return new String[]{timestamp, sigNonce, signature, body};
    }

    @Test
    @DisplayName("回调验签通过 + AES-GCM 解密：返回商户单号/交易状态/微信单号")
    void verifyAndDecryptCallbackSuccess() throws Exception {
        String[] cb = buildSignedCallback(
                "{\"out_trade_no\":\"NO123\",\"trade_state\":\"SUCCESS\",\"transaction_id\":\"TX001\"}");

        Map<String, Object> result = client.verifyAndDecryptCallback(cb[0], cb[1], cb[2], cb[3]);

        assertThat(result.get("out_trade_no")).isEqualTo("NO123");
        assertThat(result.get("trade_state")).isEqualTo("SUCCESS");
        assertThat(result.get("transaction_id")).isEqualTo("TX001");
    }

    @Test
    @DisplayName("回调签名被篡改 → WECHAT_PAY_FAILED 拒绝")
    void callbackTamperedSignature() throws Exception {
        String[] cb = buildSignedCallback("{\"out_trade_no\":\"NO123\",\"trade_state\":\"SUCCESS\"}");
        byte[] tampered = Base64.getDecoder().decode(cb[2]);
        tampered[tampered.length - 1] ^= 0x01;
        String badSig = Base64.getEncoder().encodeToString(tampered);

        assertThatThrownBy(() -> client.verifyAndDecryptCallback(cb[0], cb[1], badSig, cb[3]))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1037));
    }

    @Test
    @DisplayName("非本平台签发的回调（签名密钥不符）→ WECHAT_PAY_FAILED 拒绝")
    void callbackWrongSigner() throws Exception {
        String[] cb = buildSignedCallback("{\"out_trade_no\":\"NO123\",\"trade_state\":\"SUCCESS\"}");
        // 用商户私钥（而非平台私钥）重签 → 平台公钥验签失败
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(merchantKey.getPrivate());
        signer.update((cb[0] + "\n" + cb[1] + "\n" + cb[3] + "\n").getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> client.verifyAndDecryptCallback(cb[0], cb[1],
                Base64.getEncoder().encodeToString(signer.sign()), cb[3]))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1037));
    }

    /* ================= 凭据 fail-fast ================= */

    @Test
    @DisplayName("配置 mchid 但其余凭据缺失：构造即 IllegalStateException（fail-fast）")
    void constructorFailsFastOnBlankCredentials() {
        RestClient.Builder builder = RestClient.builder();
        assertThatThrownBy(() -> new WechatPayClientReal(APPID, MCHID, API_V3_KEY, SERIAL,
                pem("PRIVATE KEY", merchantKey.getPrivate().getEncoded()), "", "https://api.example.com", builder))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform-public-key");
    }
}
