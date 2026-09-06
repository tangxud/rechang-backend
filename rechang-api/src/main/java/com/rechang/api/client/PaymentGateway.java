package com.rechang.api.client;

import com.rechang.api.entity.OrderEntity;
import com.rechang.api.vo.PayParamsVO;

import java.util.Map;

/**
 * 支付网关抽象（PRD 决策 #2：微信支付 + 抽象网关，预留支付宝/银联扩展）。
 * dev/本地未配置商户号时由 {@link com.rechang.api.mock.WechatPayMock} 提供支付即成功语义；
 * 配置 wechat-pay.mchid 后启用 {@link WechatPayClientReal}（微信支付 v3）。
 */
public interface PaymentGateway {

    /**
     * 统一下单，返回小程序拉起收银台所需参数（timeStamp/nonceStr/package/signType/paySign）
     *
     * @param order  待支付订单（totalAmount 为实付金额，单位分）
     * @param openid 支付用户 openid（JSAPI 下单必填）
     */
    PayParamsVO createPayParams(OrderEntity order, String openid);

    /**
     * 验签并解密支付回调通知，返回 {out_trade_no, trade_state, transaction_id}
     */
    Map<String, Object> verifyAndDecryptCallback(String timestamp, String nonce, String signature, String body);

    /**
     * 是否"支付即成功"（无真实回调链路）：mock 网关返回 true，pay() 同步推进订单状态；
     * 真实网关返回 false，状态推进只能由回调驱动
     */
    default boolean settlesImmediately() {
        return false;
    }
}
