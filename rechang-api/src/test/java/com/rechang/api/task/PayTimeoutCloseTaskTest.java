package com.rechang.api.task;

import com.rechang.api.entity.OrderEntity;
import com.rechang.api.entity.Ticket;
import com.rechang.api.mapper.OrderMapper;
import com.rechang.api.mapper.TicketMapper;
import com.rechang.api.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 支付超时自动关单（票 #30005 验收 3）：15 分钟未支付关闭（与座位锁 TTL 一致），
 * 乐观锁冲突（并发已支付/取消）跳过，单笔失败不阻断批次。
 */
@ExtendWith(MockitoExtension.class)
class PayTimeoutCloseTaskTest {

    @Mock OrderMapper orderMapper;
    @Mock TicketMapper ticketMapper;
    @Mock RedisTemplate<String, Object> redisTemplate;
    @InjectMocks PayTimeoutCloseTask task;

    @BeforeEach
    void setUp() {
        // 单测无 Spring 代理，self 直指本实例（@Transactional 在单测中不生效，与既有单测口径一致）
        ReflectionTestUtils.setField(task, "self", task);
    }

    @Test
    @DisplayName("超时订单：置 CANCELLED+TIMEOUT、物理删票、释放座位锁；无座票不动 Redis")
    void closesExpiredOrder() {
        OrderEntity order = Fixtures.order(9L, Fixtures.USER_A, Fixtures.PERF_ID, "PENDING_PAY");
        order.setOrderNo("NO123");
        Ticket seated = Fixtures.ticket(1L, 9L, Fixtures.PERF_ID, "PENDING");
        seated.setSeatId(101L);
        Ticket unseated = Fixtures.ticket(2L, 9L, Fixtures.PERF_ID, "PENDING");
        lenient().when(orderMapper.selectList(any())).thenReturn(List.of(order));
        when(orderMapper.updateById(order)).thenReturn(1);
        when(ticketMapper.selectList(any())).thenReturn(List.of(seated, unseated));

        task.closeExpiredOrders();

        verify(orderMapper).updateById(order);
        verify(ticketMapper).delete(any());
        verify(redisTemplate).delete("seat:lock:" + Fixtures.PERF_ID + ":101");
        org.assertj.core.api.Assertions.assertThat(order.getCancelReason()).isEqualTo("TIMEOUT");
        org.assertj.core.api.Assertions.assertThat(order.getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("无超时订单：本轮空转，不触发任何写操作")
    void noExpiredOrders() {
        when(orderMapper.selectList(any())).thenReturn(List.of());

        task.closeExpiredOrders();

        verifyNoInteractions(ticketMapper, redisTemplate);
        verify(orderMapper, never()).updateById(any(OrderEntity.class));
    }

    @Test
    @DisplayName("乐观锁冲突（回调已支付/用户已取消）：跳过关单，不删票")
    void skipsOnOptimisticLockConflict() {
        OrderEntity order = Fixtures.order(9L, Fixtures.USER_A, Fixtures.PERF_ID, "PENDING_PAY");
        lenient().when(orderMapper.selectList(any())).thenReturn(List.of(order));
        when(orderMapper.updateById(order)).thenReturn(0);

        task.closeExpiredOrders();

        verify(ticketMapper, never()).delete(any());
    }

    @Test
    @DisplayName("单笔异常不阻断批次：第二笔仍被处理")
    void singleFailureDoesNotBlockBatch() {
        OrderEntity first = Fixtures.order(9L, Fixtures.USER_A, Fixtures.PERF_ID, "PENDING_PAY");
        OrderEntity second = Fixtures.order(10L, Fixtures.USER_A, Fixtures.PERF_ID, "PENDING_PAY");
        lenient().when(orderMapper.selectList(any())).thenReturn(List.of(first, second));
        lenient().when(ticketMapper.selectList(any()))
                .thenThrow(new RuntimeException("redis down"))
                .thenReturn(List.of());

        task.closeExpiredOrders();

        verify(orderMapper).updateById(first);
        verify(orderMapper).updateById(second);
    }
}
