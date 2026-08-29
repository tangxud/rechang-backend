package com.rechang.api.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rechang.common.exception.BusinessException;
import com.rechang.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Map;

/**
 * 微信登录真实实现：jscode2session 换取 openid/session_key，
 * getPhoneNumber 密文经 AES-128-CBC(session_key) 解密并校验水印 appid。
 * 凭据来自 WECHAT_APPID / WECHAT_SECRET 环境变量，test/prod 缺失即启动失败（见 application yml wechat 段）。
 */
@Slf4j
@Component
@Profile("!dev")
public class WechatLoginClientReal implements WechatLoginClient {

    private static final String JSCODE2SESSION_PATH = "/sns/jscode2session";
    private static final int HTTP_TIMEOUT_MILLIS = 5000;

    private final String appid;
    private final String secret;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WechatLoginClientReal(@Value("${wechat.appid:}") String appid,
                                 @Value("${wechat.secret:}") String secret,
                                 RestClient.Builder restClientBuilder) {
        this(appid, secret, restClientBuilder
                .baseUrl("https://api.weixin.qq.com")
                .requestFactory(defaultRequestFactory())
                .build());
    }

    WechatLoginClientReal(String appid, String secret, RestClient restClient) {
        if (appid == null || appid.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalStateException("微信凭据未配置：非 dev 环境必须提供 WECHAT_APPID / WECHAT_SECRET 环境变量");
        }
        this.appid = appid;
        this.secret = secret;
        this.restClient = restClient;
    }

    private static SimpleClientHttpRequestFactory defaultRequestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(HTTP_TIMEOUT_MILLIS);
        requestFactory.setReadTimeout(HTTP_TIMEOUT_MILLIS);
        return requestFactory;
    }

    @Override
    public Map<String, Object> code2session(String code) {
        String body;
        try {
            body = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path(JSCODE2SESSION_PATH)
                            .queryParam("appid", appid)
                            .queryParam("secret", secret)
                            .queryParam("js_code", code)
                            .queryParam("grant_type", "authorization_code")
                            .build())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            log.warn("微信 jscode2session 调用失败: {}", e.getMessage());
            throw new BusinessException(ResultCode.WECHAT_AUTH_FAILED, "微信登录接口调用失败");
        }
        JsonNode node = readJson(body, ResultCode.WECHAT_AUTH_FAILED);

        int errcode = node.path("errcode").asInt(0);
        if (errcode != 0) {
            log.warn("微信 jscode2session 失败: errcode={}, errmsg={}", errcode, node.path("errmsg").asText(""));
            throw new BusinessException(ResultCode.WECHAT_AUTH_FAILED,
                    "微信登录失败(" + errcode + "): " + node.path("errmsg").asText(""));
        }
        String openid = node.path("openid").asText(null);
        String sessionKey = node.path("session_key").asText(null);
        if (openid == null || openid.isBlank() || sessionKey == null || sessionKey.isBlank()) {
            throw new BusinessException(ResultCode.WECHAT_AUTH_FAILED, "微信登录响应缺少 openid/session_key");
        }
        return Map.of("openid", openid,
                "session_key", sessionKey,
                "unionid", node.path("unionid").asText(""));
    }

    @Override
    public String decryptPhone(String sessionKey, String encryptedData, String iv) {
        if (sessionKey == null || sessionKey.isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "登录态已过期，请重新登录后绑定手机号");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(Base64.getDecoder().decode(sessionKey), "AES"),
                    new IvParameterSpec(Base64.getDecoder().decode(iv)));
            String plain = new String(cipher.doFinal(Base64.getDecoder().decode(encryptedData)), StandardCharsets.UTF_8);
            JsonNode node = readJson(plain, ResultCode.WECHAT_DECRYPT_FAILED);

            if (!appid.equals(node.path("watermark").path("appid").asText(""))) {
                throw new BusinessException(ResultCode.WECHAT_DECRYPT_FAILED, "微信数据水印校验失败");
            }
            String phone = node.path("phoneNumber").asText("");
            if (phone.isBlank()) {
                throw new BusinessException(ResultCode.WECHAT_DECRYPT_FAILED, "解密数据中缺少手机号");
            }
            return phone;
        } catch (BusinessException e) {
            throw e;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.warn("微信手机号解密失败: {}", e.getMessage());
            throw new BusinessException(ResultCode.WECHAT_DECRYPT_FAILED, "微信数据解密失败");
        }
    }

    private JsonNode readJson(String body, ResultCode fallbackCode) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new BusinessException(fallbackCode, "微信接口响应解析失败");
        }
    }
}
