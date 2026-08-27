package com.rechang.api.service;

import com.rechang.api.dto.CreateOrderDTO;
import com.rechang.api.entity.OrderEntity;
import com.rechang.api.entity.Performance;
import com.rechang.api.entity.PerformancePriceZone;
import com.rechang.api.entity.Seat;
import com.rechang.api.entity.Ticket;
import com.rechang.api.mapper.AttendeeMapper;
import com.rechang.api.mapper.OrderMapper;
import com.rechang.api.mapper.PerformanceMapper;
import com.rechang.api.mapper.PerformancePriceZoneMapper;
import com.rechang.api.mapper.PerformanceReviewMapper;
import com.rechang.api.mapper.SeatMapper;
import com.rechang.api.mapper.TicketMapper;
import com.rechang.api.mapper.VenueMapper;
import com.rechang.api.support.Fixtures;
import com.rechang.api.vo.PayParamsVO;
import com.rechang.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下单守卫树（演出状态/站票/座位双重库存/限购/观演人）与支付、取消状态机。
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final String LOCK_KEY = "seat:lock:" + Fixtures.PERF_ID + ":101";

    @Mock OrderMapper orderMapper;
    @Mock TicketMapper ticketMapper;
    @Mock PerformanceMapper performanceMapper;
    @Mock VenueMapper venueMapper;
    @Mock SeatMapper seatMapper;
    @Mock PerformancePriceZoneMapper priceZoneMapper;
    @Mock AttendeeMapper attendeeMapper;
    @Mock PerformanceReviewMapper performanceReviewMapper;
    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock ValueOperations<String, Object> valueOps;
    @InjectMocks OrderService orderService;

    private Performance perf;
    private List<PerformancePriceZone> zones;

    @BeforeEach
    void setUp() {
        lenient().doReturn(valueOps).when(redisTemplate).opsForValue();
        perf = Fixtures.performance("ON_SALE");
        zones = List.of(
                Fixtures.zone("A", 58000),
                Fixtures.zone("B", 38000),
                Fixtures.zone("C", 18000));
        lenient().when(performanceMapper.selectById(Fixtures.PERF_ID)).thenReturn(perf);
        lenient().when(priceZoneMapper.selectList(any())).thenReturn(zones);
        // 默认限购内：无历史持有票
        lenient().when(ticketMapper.selectCount(any())).thenReturn(0L);
        lenient().when(orderMapper.insert(any(OrderEntity.class))).thenAnswer(inv -> {
            inv.getArgument(0, OrderEntity.class).setId(999L);
            return 1;
        });
        lenient().when(ticketMapper.insert(any(Ticket.class))).thenReturn(1);
        lenient().when(redisTemplate.hasKey(anyString())).thenReturn(false);
        lenient().when(seatMapper.selectBatchIds(any())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream().map(id -> Fixtures.seat(id, "A", "1", id.toString(), "ENABLED")).toList();
        });
    }

    private CreateOrderDTO seated(Long... seatIds) {
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setPerformanceId(Fixtures.PERF_ID);
        dto.setSeatIds(List.of(seatIds));
        return dto;
    }

    private CreateOrderDTO standing(int count) {
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setPerformanceId(Fixtures.PERF_ID);
        dto.setStandingCount(count);
        return dto;
    }

    /* ================= createOrder 守卫树 ================= */

    @Test
    @DisplayName("演出不存在或非 ON_SALE → PERFORMANCE_NOT_FOUND")
    void perfMustBeOnSale() {
        perf.setPublishStatus("DRAFT");
        assertThatThrownBy(() -> orderService.createOrder(standing(2), Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 1005);

        when(performanceMapper.selectById(Fixtures.PERF_ID)).thenReturn(null);
        assertThatThrownBy(() -> orderService.createOrder(standing(2), Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 1005);
    }

    @Test
    @DisplayName("站票数量必须大于 0")
    void standingCountPositive() {
        assertThatThrownBy(() -> orderService.createOrder(standing(0), Fixtures.USER_A))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("站票数量");
    }

    @Test
    @DisplayName("站票价格取 minPrice，为 null 时回退到价格区最低价")
    void standingPriceFallback() {
        var vo = orderService.createOrder(standing(2), Fixtures.USER_A);
        assertThat(vo.getTotalAmount()).isEqualTo(18000 * 2);

        ArgumentCaptor<Ticket> cap = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketMapper, org.mockito.Mockito.times(2)).insert(cap.capture());
        assertThat(cap.getAllValues()).allSatisfy(t -> {
            assertThat(t.getFaceAmount()).isEqualTo(18000);
            assertThat(t.getSeatId()).isNull();
            assertThat(t.getStatus()).isEqualTo("PENDING");
        });

        perf.setMinPrice(null);
        orderService.createOrder(standing(1), Fixtures.USER_A);
        ArgumentCaptor<Ticket> cap2 = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketMapper, org.mockito.Mockito.times(3)).insert(cap2.capture());
        assertThat(cap2.getAllValues().get(cap2.getAllValues().size() - 1).getFaceAmount()).isEqualTo(18000);
    }

    @Nested
    class SeatedGuards {
        @Test
        @DisplayName("Redis 锁存在 → SEAT_LOCKED（预检先行）")
        void redisLockPrecheck() {
            when(redisTemplate.hasKey(LOCK_KEY)).thenReturn(true);
            assertThatThrownBy(() -> orderService.createOrder(seated(101L), Fixtures.USER_A))
                    .matches(e -> ((BusinessException) e).getCode() == 1013);
        }

        @Test
        @DisplayName("DB 已售 → SEAT_SOLD")
        void dbSoldCheck() {
            Ticket sold = Fixtures.ticket(500L, 50L, Fixtures.PERF_ID, "USABLE");
            sold.setSeatId(202L);
            when(ticketMapper.selectList(any())).thenReturn(List.of(sold));
            assertThatThrownBy(() -> orderService.createOrder(seated(202L), Fixtures.USER_A))
                    .matches(e -> ((BusinessException) e).getCode() == 1014);
        }

        @Test
        @DisplayName("座位不存在 / DISABLED / 区域未定价 分别拒绝")
        void seatValidation() {
            doReturn(List.of()).when(seatMapper).selectBatchIds(any());

            assertThatThrownBy(() -> orderService.createOrder(seated(404L), Fixtures.USER_A))
                    .hasMessageContaining("座位不存在");

            Seat disabled = Fixtures.seat(404L, "A", "1", "1", "DISABLED");
            doReturn(List.of(disabled)).when(seatMapper).selectBatchIds(any());
            assertThatThrownBy(() -> orderService.createOrder(seated(404L), Fixtures.USER_A))
                    .hasMessageContaining("座位不可用");

            Seat unpriced = Fixtures.seat(404L, "X", "1", "1", "ENABLED");
            doReturn(List.of(unpriced)).when(seatMapper).selectBatchIds(any());
            assertThatThrownBy(() -> orderService.createOrder(seated(404L), Fixtures.USER_A))
                    .hasMessageContaining("区域未定价");
        }
    }

    @Test
    @DisplayName("限购：已有张数 + 本次张数 > limit 拒绝；等于限额边界放行（含 TRANSFERRED/PENDING 占额度）")
    void purchaseLimitBoundary() {
        perf.setPurchaseLimitPerId(3);

        when(ticketMapper.selectCount(any())).thenReturn(2L);   // 已持 TRANSFERRED 票也算额度
        assertThatThrownBy(() -> orderService.createOrder(seated(101L, 102L), Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 1015);

        when(ticketMapper.selectCount(any())).thenReturn(2L);
        orderService.createOrder(seated(101L), Fixtures.USER_A); // 2+1 == 3 放行
        verify(orderMapper, org.mockito.Mockito.atLeastOnce()).insert(any(OrderEntity.class));

        perf.setPurchaseLimitPerId(null);                        // null → 默认限 4
        when(ticketMapper.selectCount(any())).thenReturn(4L);
        assertThatThrownBy(() -> orderService.createOrder(seated(101L), Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 1015);
    }

    @Test
    @DisplayName("观演人必须属于本人；非空列表中混入 null 观演人 ID 报错")
    void attendeeValidation() {
        // 该用户名下无此观演人 → 不存在
        when(attendeeMapper.selectList(any())).thenReturn(List.of());
        CreateOrderDTO dto = seated(101L);
        CreateOrderDTO.AttendeeItem item = new CreateOrderDTO.AttendeeItem();
        item.setAttendeeId(777L);
        dto.setAttendees(List.of(item));
        assertThatThrownBy(() -> orderService.createOrder(dto, Fixtures.USER_A))
                .hasMessageContaining("观演人不存在");

        // 服务端仅对「存在至少一个非空 id 的列表」逐条校验：混入的 null 项触发显式报错
        var owner = Fixtures.attendee(777L, Fixtures.USER_A, "张三", "hash-777", 0);
        when(attendeeMapper.selectList(any())).thenReturn(List.of(owner));
        CreateOrderDTO.AttendeeItem blank = new CreateOrderDTO.AttendeeItem();
        CreateOrderDTO twoSeatsDto = seated(101L, 102L);
        twoSeatsDto.setAttendees(List.of(item, blank));
        assertThatThrownBy(() -> orderService.createOrder(twoSeatsDto, Fixtures.USER_A))
                .hasMessageContaining("观演人ID不能为空");
    }

    @Test
    @DisplayName("下单成功（坐票）：总额=区域价合计，订单初始 PENDING_PAY/version=0，锁 TTL=15min")
    void createSeatedOrderHappyPath() {
        var vo = orderService.createOrder(seated(101L, 102L), Fixtures.USER_A);

        ArgumentCaptor<OrderEntity> orderCap = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderMapper).insert(orderCap.capture());
        OrderEntity inserted = orderCap.getValue();
        assertThat(inserted.getTotalAmount()).isEqualTo(58000 * 2); // A 区两张
        assertThat(inserted.getStatus()).isEqualTo("PENDING_PAY");
        assertThat(inserted.getSource()).isEqualTo("PURCHASE");
        assertThat(inserted.getVersion()).isZero();
        assertThat(inserted.getRefundedAmount()).isZero();
        assertThat(vo.getId()).isEqualTo(999L);

        verify(ticketMapper, org.mockito.Mockito.times(2)).insert(any(Ticket.class));
        verify(valueOps).set(LOCK_KEY, "LOCKED", 15L, TimeUnit.MINUTES);
        verify(valueOps).set("seat:lock:" + Fixtures.PERF_ID + ":102", "LOCKED", 15L, TimeUnit.MINUTES);
    }

    @Test
    @DisplayName("无观演人时票的身份证 hash 置空串")
    void noAttendeesHashBlank() {
        ArgumentCaptor<Ticket> cap = ArgumentCaptor.forClass(Ticket.class);
        orderService.createOrder(seated(101L), Fixtures.USER_A);
        verify(ticketMapper).insert(cap.capture());
        assertThat(cap.getValue().getAttendeeIdCardHash()).isEqualTo("");
    }

    /* ================= pay / cancel 状态机 ================= */

    private OrderEntity pendingOrder() {
        OrderEntity o = Fixtures.order(Fixtures.ORDER_ID, Fixtures.USER_A, Fixtures.PERF_ID, "PENDING_PAY");
        when(orderMapper.selectById(Fixtures.ORDER_ID)).thenReturn(o);
        return o;
    }

    @Test
    @DisplayName("仅 PENDING_PAY 可支付")
    void payOnlyPending() {
        OrderEntity issued = Fixtures.order(Fixtures.ORDER_ID, Fixtures.USER_A, Fixtures.PERF_ID, "ISSUED");
        when(orderMapper.selectById(Fixtures.ORDER_ID)).thenReturn(issued);
        assertThatThrownBy(() -> orderService.pay(Fixtures.ORDER_ID, Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 1012)
                .hasMessageContaining("待支付");
    }

    @Test
    @DisplayName("支付成功：订单 ISSUED+paidAt+渠道，整单票批量 USABLE，座位锁全删，返回 mock 支付参数")
    void paySuccess() {
        OrderEntity order = pendingOrder();
        Ticket t1 = Fixtures.ticket(1L, Fixtures.ORDER_ID, Fixtures.PERF_ID, "PENDING");
        t1.setSeatId(101L);
        when(ticketMapper.selectList(any())).thenReturn(List.of(t1));

        PayParamsVO params = orderService.pay(Fixtures.ORDER_ID, Fixtures.USER_A);

        assertThat(order.getStatus()).isEqualTo("ISSUED");
        assertThat(order.getPaidAt()).isNotNull();
        assertThat(order.getPayChannel()).isEqualTo("WECHAT");

        verify(ticketMapper).update(any(Ticket.class), any());
        verify(redisTemplate).delete(LOCK_KEY);
        assertThat(params.getTimeStamp()).isEqualTo(String.valueOf(System.currentTimeMillis() / 1000));
        assertThat(params.getSignType()).isEqualTo("RSA");
        assertThat(params.getPackageStr()).startsWith("prepay_id=wx");
    }

    @Test
    @DisplayName("取消：置 CANCELLED + 物理删除 ticket + 释放座位锁")
    void cancelSuccess() {
        OrderEntity order = pendingOrder();
        Ticket t1 = Fixtures.ticket(1L, Fixtures.ORDER_ID, Fixtures.PERF_ID, "PENDING");
        t1.setSeatId(101L);
        when(ticketMapper.selectList(any())).thenReturn(List.of(t1));

        orderService.cancelOrder(Fixtures.ORDER_ID, Fixtures.USER_A);

        assertThat(order.getStatus()).isEqualTo("CANCELLED");
        assertThat(order.getCancelReason()).isEqualTo("USER");
        assertThat(order.getCancelledAt()).isNotNull();
        verify(ticketMapper).delete(any());
        verify(redisTemplate).delete(LOCK_KEY);
    }

    @Test
    @DisplayName("取消非待支付订单拒绝")
    void cancelOnlyPending() {
        OrderEntity paid = Fixtures.order(Fixtures.ORDER_ID, Fixtures.USER_A, Fixtures.PERF_ID, "ISSUED");
        when(orderMapper.selectById(Fixtures.ORDER_ID)).thenReturn(paid);
        assertThatThrownBy(() -> orderService.cancelOrder(Fixtures.ORDER_ID, Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 1012);
        verify(ticketMapper, never()).delete(any());
    }

    @Test
    @DisplayName("支付状态查询：已支付态集合判定")
    void payStatusMapping() {
        for (String s : List.of("ISSUED", "ATTENDED", "REVIEWED")) {
            OrderEntity o = Fixtures.order(Fixtures.ORDER_ID, Fixtures.USER_A, Fixtures.PERF_ID, s);
            when(orderMapper.selectById(Fixtures.ORDER_ID)).thenReturn(o);
            assertThat(orderService.getPayStatus(Fixtures.ORDER_ID, Fixtures.USER_A).get("paid")).isEqualTo(true);
        }
        OrderEntity pending = Fixtures.order(Fixtures.ORDER_ID, Fixtures.USER_A, Fixtures.PERF_ID, "PENDING_PAY");
        when(orderMapper.selectById(Fixtures.ORDER_ID)).thenReturn(pending);
        assertThat(orderService.getPayStatus(Fixtures.ORDER_ID, Fixtures.USER_A).get("paid")).isEqualTo(false);
    }
}
