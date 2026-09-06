package com.rechang.api.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rechang.api.entity.OrderEntity;
import com.rechang.api.vo.PayParamsVO;
import com.rechang.common.exception.BusinessException;
import com.rechang.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 微信支付 v3 真实实现（JSAPI）：统一下单 + 小程序拉收银台签名 + 回调验签解密。
 * 配置 wechat-pay.mchid 即启用（任意 profile）；凭据缺失时其余项构造即 fail-fast。
 * 简化说明：回调验签使用注入的平台公钥（PEM），未实现 /v3/certificates 平台证书轮换——商户号就绪后的迭代项。
 */
@Slf4j
public class WechatPayClientReal implements PaymentGateway {

    private static final String JSAPI_PATH = "/v3/pay/transactions/jsapi";
    private static final int HTTP_TIMEOUT_MILLIS = 5000;
    private static final int AES_GCM_TAG_BITS = 128;

    private final String appid;
    private final String mchid;
    private final byte[] apiV3Key;
    private final String merchantSerialNo;
    private final PrivateKey merchantPrivateKey;
    private final PublicKey platformPublicKey;
    private final String notifyUrl;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WechatPayClientReal(String appid, String mchid, String apiV3Key, String merchantSerialNo,
                               String merchantPrivateKeyPem, String platformPublicKeyPem, String notifyUrl,
                               RestClient.Builder restClientBuilder) {
        this(appid, mchid, apiV3Key, merchantSerialNo, merchantPrivateKeyPem, platformPublicKeyPem, notifyUrl,
                restClientBuilder.baseUrl("https://api.mch.weixin.qq.com")
                        .requestFactory(defaultRequestFactory()).build());
    }

    /** 测试入口：直接传入（可绑定 MockRestServiceServer 的）RestClient，避免覆盖 Mock 工厂 */
    WechatPayClientReal(String appid, String mchid, String apiV3Key, String merchantSerialNo,
                        String merchantPrivateKeyPem, String platformPublicKeyPem, String notifyUrl,
                        RestClient restClient) {
        if (isBlank(appid) || isBlank(mchid) || isBlank(apiV3Key) || isBlank(merchantSerialNo)
                || isBlank(merchantPrivateKeyPem) || isBlank(platformPublicKeyPem) || isBlank(notifyUrl)) {
            throw new IllegalStateException("微信支付凭据未配置：wechat-pay.* 需要 mchid/api-v3-key/merchant-serial-no/"
                    + "merchant-private-key/platform-public-key/notify-url 全部提供");
        }
        this.appid = appid;
        this.mchid = mchid;
        this.apiV3Key = apiV3Key.getBytes(StandardCharsets.UTF_8);
        this.merchantSerialNo = merchantSerialNo;
        this.merchantPrivateKey = parsePrivateKey(merchantPrivateKeyPem);
        this.platformPublicKey = parsePublicKey(platformPublicKeyPem);
        this.notifyUrl = notifyUrl;
        this.restClient = restClient;
    }

    private static SimpleClientHttpRequestFactory defaultRequestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(HTTP_TIMEOUT_MILLIS);
        requestFactory.setReadTimeout(HTTP_TIMEOUT_MILLIS);
        return requestFactory;
    }

    /* ================= 统一下单 ================= */

    @Override
    public PayParamsVO createPayParams(OrderEntity order, String openid) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("appid", appid);
        body.put("mchid", mchid);
        body.put("description", "热场票务-订单" + order.getOrderNo());
        body.put("out_trade_no", order.getOrderNo());
        body.put("notify_url", notifyUrl);
        body.putObject("amount").put("total", order.getTotalAmount()).put("currency", "CNY");
        body.putObject("payer").put("openid", openid);
        String bodyStr = body.toString();

        JsonNode resp = postJsapi(bodyStr);
        String prepayId = resp.path("prepay_id").asText("");
        if (prepayId.isBlank()) {
            throw new BusinessException(ResultCode.WECHAT_PAY_FAILED, "统一下单响应缺少 prepay_id");
        }

        // 小程序拉起收银台签名：appId\ntimeStamp\nnonceStr\npackage\n
        String pkg = "prepay_id=" + prepayId;
        String timeStamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonceStr = UUID.randomUUID().toString().replace("-", "");
        PayParamsVO vo = new PayParamsVO();
        vo.setTimeStamp(timeStamp);
        vo.setNonceStr(nonceStr);
        vo.setPackageStr(pkg);
        vo.setSignType("RSA");
        vo.setPaySign(sign("appId\n" + timeStamp + "\n" + nonceStr + "\n" + pkg + "\n", merchantPrivateKey));
        return vo;
    }

    private JsonNode postJsapi(String bodyStr) {
        try {
            String resp = restClient.post()
                    .uri(JSAPI_PATH)
                    .header("Authorization", authorization("POST", JSAPI_PATH, bodyStr))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .body(bodyStr)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(resp == null ? "" : resp);
        } catch (RestClientException e) {
            log.warn("微信统一下单调用失败: {}", e.getMessage());
            throw new BusinessException(ResultCode.WECHAT_PAY_FAILED, "微信支付接口调用失败");
        } catch (Exception e) {
            log.warn("微信统一下单响应处理失败: {}", e.toString());
            throw new BusinessException(ResultCode.WECHAT_PAY_FAILED, "微信支付响应解析失败");
        }
    }

    /** v3 请求头签名：SHA256withRSA 对 "方法\n路径\n时间戳\n随机串\n请求体\n" 签名 */
    private String authorization(String method, String path, String bodyStr) {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String signature = sign(method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodyStr + "\n",
                merchantPrivateKey);
        return "WECHATPAY2-SHA256-RSA2048 mchid=\"" + mchid + "\",nonce_str=\"" + nonce
                + "\",timestamp=\"" + timestamp + "\",serial_no=\"" + merchantSerialNo
                + "\",signature=\"" + signature + "\"";
    }

    /* ================= 回调验签 + 解密 ================= */

    @Override
    public Map<String, Object> verifyAndDecryptCallback(String timestamp, String nonce, String signature, String body) {
        // 1. 验签：SHA256withRSA 平台公钥对 "时间戳\n随机串\n报文主体\n" 校验 Wechatpay-Signature
        boolean verified;
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(platformPublicKey);
            verifier.update((timestamp + "\n" + nonce + "\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
            verified = verifier.verify(Base64.getDecoder().decode(signature));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new BusinessException(ResultCode.WECHAT_PAY_FAILED, "回调验签失败");
        }
        if (!verified) {
            throw new BusinessException(ResultCode.WECHAT_PAY_FAILED, "回调验签失败：签名不匹配");
        }

        // 2. 解密 resource：AES-256-GCM(key=APIv3密钥, iv=nonce, aad=associated_data)
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode resource = root.path("resource");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(apiV3Key, "AES"),
                    new GCMParameterSpec(AES_GCM_TAG_BITS, resource.path("nonce").asText().getBytes(StandardCharsets.UTF_8)));
            cipher.updateAAD(resource.path("associated_data").asText("").getBytes(StandardCharsets.UTF_8));
            String plain = new String(cipher.doFinal(Base64.getDecoder().decode(resource.path("ciphertext").asText())),
                    StandardCharsets.UTF_8);

            JsonNode paid = objectMapper.readTree(plain);
            Map<String, Object> result = new HashMap<>();
            result.put("out_trade_no", paid.path("out_trade_no").asText(""));
            result.put("trade_state", paid.path("trade_state").asText(""));
            result.put("transaction_id", paid.path("transaction_id").asText(""));
            return result;
        } catch (GeneralSecurityException | IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("微信回调解密失败: {}", e.getMessage());
            throw new BusinessException(ResultCode.WECHAT_PAY_FAILED, "回调解密失败");
        }
    }

    /* ================= 工具 ================= */

    private String sign(String message, PrivateKey key) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new BusinessException(ResultCode.WECHAT_PAY_FAILED, "微信支付签名失败");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String pemBody(String pem) {
        return pem.replaceAll("-----[A-Z ]*-----", "").replaceAll("\\s", "");
    }

    private static PrivateKey parsePrivateKey(String pem) {
        try {
            byte[] der = Base64.getDecoder().decode(pemBody(pem));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("微信支付商户私钥解析失败（需 PKCS#8 PEM）", e);
        }
    }

    private static PublicKey parsePublicKey(String pem) {
        try {
            byte[] der = Base64.getDecoder().decode(pemBody(pem));
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("微信支付平台公钥解析失败（需 X.509 PEM）", e);
        }
    }
}
