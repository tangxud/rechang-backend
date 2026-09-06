package com.rechang.api.controller.c;

import com.rechang.api.client.PaymentGateway;
import com.rechang.api.service.WechatPayNotifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 微信支付回调通知端点（permitAll，验签由 PaymentGateway 承担）。
 * 返回 200 {"code":"SUCCESS"} 表示受理；处理失败返回 5xx 触发微信衰减重试。
 * Mock 网关下可本地 curl 直塞 {"out_trade_no":"...","trade_state":"SUCCESS"} 模拟回调。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PayNotifyController {

    private final PaymentGateway paymentGateway;
    private final WechatPayNotifyService wechatPayNotifyService;

    @PostMapping("/api/pay/wechat/notify")
    public ResponseEntity<Map<String, String>> wechatNotify(
            @RequestHeader(value = "Wechatpay-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "Wechatpay-Nonce", required = false) String nonce,
            @RequestHeader(value = "Wechatpay-Signature", required = false) String signature,
            @RequestBody String body) {
        try {
            Map<String, Object> result = paymentGateway.verifyAndDecryptCallback(timestamp, nonce, signature, body);
            String tradeState = (String) result.get("trade_state");
            String outTradeNo = (String) result.get("out_trade_no");
            if (!"SUCCESS".equals(tradeState)) {
                log.info("微信支付回调非成功状态: orderNo={}, tradeState={}", outTradeNo, tradeState);
                return ok();
            }
            wechatPayNotifyService.onPaySuccess(outTradeNo, (String) result.get("transaction_id"));
            return ok();
        } catch (Exception e) {
            log.error("微信支付回调处理失败: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("code", "FAIL", "message", "回调处理失败"));
        }
    }

    private static ResponseEntity<Map<String, String>> ok() {
        return ResponseEntity.ok(Map.of("code", "SUCCESS", "message", "成功"));
    }
}
