package com.rechang.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rechang.api.entity.OrderEntity;
import com.rechang.api.mapper.OrderMapper;
import com.rechang.common.exception.BusinessException;
import com.rechang.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 微信支付回调业务处理：按商户单号（out_trade_no = order_no）幂等推进订单支付成功。
 * 失败抛异常 → 回调端点返回 5xx，微信按衰减重试策略重发通知。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatPayNotifyService {

    private final OrderMapper orderMapper;
    private final OrderService orderService;

    @Transactional
    public void onPaySuccess(String outTradeNo, String transactionId) {
        OrderEntity order = orderMapper.selectOne(new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getOrderNo, outTradeNo)
                .last("LIMIT 1"));
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND, "回调订单不存在：" + outTradeNo);
        }
        log.info("微信支付回调推进订单: orderNo={}, transactionId={}, currentStatus={}",
                outTradeNo, transactionId, order.getStatus());
        // advanceOrderToIssued 内含已支付态幂等放行、取消态抛错（资损风险：支付成功但订单已关，需人工介入）
        orderService.advanceOrderToIssued(order, "WECHAT");
    }
}
