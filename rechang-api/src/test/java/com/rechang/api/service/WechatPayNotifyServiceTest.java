package com.rechang.api.service;

import com.rechang.api.entity.OrderEntity;
import com.rechang.api.mapper.OrderMapper;
import com.rechang.api.support.Fixtures;
import com.rechang.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 微信支付回调幂等推进（票 #30005）：状态推进逻辑在 OrderService.advanceOrderToIssued（其冲突语义
 * 由 OrderServiceTest 覆盖），本类验证回调入口的订单定位、幂等与转发。
 */
@ExtendWith(MockitoExtension.class)
class WechatPayNotifyServiceTest {

    @Mock OrderMapper orderMapper;
    @Mock OrderService orderService;
    @InjectMocks WechatPayNotifyService notifyService;

    private OrderEntity order;

    @BeforeEach
    void setUp() {
        order = Fixtures.order(9L, Fixtures.USER_A, Fixtures.PERF_ID, "PENDING_PAY");
    }

    @Test
    @DisplayName("回调订单不存在 → ORDER_NOT_FOUND（端点转 5xx，微信重试）")
    void orderNotFound() {
        when(orderMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> notifyService.onPaySuccess("NO404", "TX1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1009))
                .hasMessageContaining("NO404");
        verifyNoInteractions(orderService);
    }

    @Test
    @DisplayName("回调定位订单后委托 advanceOrderToIssued（其内部含已支付幂等/冲突语义）")
    void delegatesToAdvance() {
        when(orderMapper.selectOne(any())).thenReturn(order);

        notifyService.onPaySuccess("NO123", "TX1");

        verify(orderService).advanceOrderToIssued(order, "WECHAT");
    }

    @Test
    @DisplayName("重复回调（订单已 ISSUED）：幂等——advance 为幂等实现，重复调用不抛错不重复出票")
    void duplicateCallbackIdempotent() {
        OrderEntity issued = Fixtures.order(9L, Fixtures.USER_A, Fixtures.PERF_ID, "ISSUED");
        when(orderMapper.selectOne(any())).thenReturn(issued);
        lenient().doNothing().when(orderService).advanceOrderToIssued(issued, "WECHAT");

        List.of(1, 2).forEach(i -> {
            assertThatCode(() -> notifyService.onPaySuccess("NO123", "TX" + i)).doesNotThrowAnyException();
        });
        // 两次回调各自定位订单并调用幂等的推进；出票副作用在 advanceOrderToIssued（OrderServiceTest 覆盖）
        verify(orderMapper, never()).updateById(any(OrderEntity.class));
    }
}
