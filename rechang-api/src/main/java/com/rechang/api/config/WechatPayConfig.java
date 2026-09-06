package com.rechang.api.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rechang.api.client.PaymentGateway;
import com.rechang.api.client.WechatPayClientReal;
import com.rechang.api.entity.OrderEntity;
import com.rechang.api.vo.PayParamsVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 支付网关装配（单工厂，规避 @ConditionalOnProperty 对空串属性的误判）：
 * <ul>
 *   <li>配置 wechat-pay.mchid → 微信支付 v3 真实网关（其余凭据缺失构造即 fail-fast）</li>
 *   <li>未配置：prod 环境启动失败（杜绝生产静默 Mock 收单）；dev/test 本地场景回落 Mock（支付即成功），
 *       覆盖个人主体无商户号、以本地测试为主的现状</li>
 * </ul>
 */
@Slf4j
@Configuration
public class WechatPayConfig {

    @Bean
    public PaymentGateway paymentGateway(
            @Value("${wechat.appid:}") String appid,
            @Value("${wechat-pay.mchid:}") String mchid,
            @Value("${wechat-pay.api-v3-key:}") String apiV3Key,
            @Value("${wechat-pay.merchant-serial-no:}") String merchantSerialNo,
            @Value("${wechat-pay.merchant-private-key:}") String merchantPrivateKey,
            @Value("${wechat-pay.platform-public-key:}") String platformPublicKey,
            @Value("${wechat-pay.notify-url:}") String notifyUrl,
            @Value("${spring.profiles.active:dev}") String activeProfile,
            org.springframework.web.client.RestClient.Builder restClientBuilder) {

        if (!mchid.isBlank()) {
            if (apiV3Key.isBlank() || merchantSerialNo.isBlank() || merchantPrivateKey.isBlank()
                    || platformPublicKey.isBlank() || notifyUrl.isBlank()) {
                throw new IllegalStateException("微信支付凭据不完整：配置 wechat-pay.mchid 后，"
                        + "api-v3-key/merchant-serial-no/merchant-private-key/platform-public-key/notify-url 必须全部提供");
            }
            log.info("微信支付真实网关已启用：mchid={}", mchid);
            return new WechatPayClientReal(appid, mchid, apiV3Key, merchantSerialNo,
                    merchantPrivateKey, platformPublicKey, notifyUrl, restClientBuilder);
        }

        if ("prod".equals(activeProfile)) {
            throw new IllegalStateException("prod 环境必须配置 wechat-pay.mchid：生产环境禁止回落 Mock 网关收单");
        }
        log.warn("[MOCK] 微信支付未配置商户号（wechat-pay.mchid），启用 Mock 网关：支付即成功，不产生真实扣款（profile={}）",
                activeProfile);
        return new WechatPayMock();
    }

    /**
     * 支付 Mock：语义为"支付即成功"——pay() 同步推进订单与票状态，无回调链路。
     * 本地也可 POST /api/pay/wechat/notify 直塞 JSON 模拟回调（验签直通解析）。
     */
    static final class WechatPayMock implements PaymentGateway {

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public PayParamsVO createPayParams(OrderEntity order, String openid) {
            log.info("[MOCK] WeChat Pay createPayParams: orderNo={}", order.getOrderNo());
            PayParamsVO vo = new PayParamsVO();
            vo.setTimeStamp(String.valueOf(System.currentTimeMillis() / 1000));
            vo.setNonceStr(UUID.randomUUID().toString().replace("-", ""));
            vo.setPackageStr("prepay_id=wx" + System.currentTimeMillis());
            vo.setSignType("RSA");
            vo.setPaySign("MOCK_SIGNATURE_" + System.currentTimeMillis());
            return vo;
        }

        @Override
        public Map<String, Object> verifyAndDecryptCallback(String timestamp, String nonce, String signature, String body) {
            // Mock 无验签：直通解析 JSON，便于本地 curl 模拟回调
            try {
                JsonNode node = objectMapper.readTree(body);
                Map<String, Object> result = new HashMap<>();
                result.put("out_trade_no", node.path("out_trade_no").asText(""));
                result.put("trade_state", node.path("trade_state").asText("SUCCESS"));
                result.put("transaction_id", node.path("transaction_id").asText(""));
                return result;
            } catch (Exception e) {
                throw new IllegalArgumentException("mock 回调体解析失败", e);
            }
        }

        @Override
        public boolean settlesImmediately() {
            return true;
        }
    }
}
