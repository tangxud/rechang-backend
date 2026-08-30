package com.rechang.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rechang.api.dto.RefundDTO;
import com.rechang.api.entity.OrderEntity;
import com.rechang.api.entity.Performance;
import com.rechang.api.entity.RefundRecord;
import com.rechang.api.entity.Seat;
import com.rechang.api.entity.Ticket;
import com.rechang.api.mapper.OrderMapper;
import com.rechang.api.mapper.PerformanceMapper;
import com.rechang.api.mapper.RefundRecordMapper;
import com.rechang.api.mapper.SeatMapper;
import com.rechang.api.mapper.TicketMapper;
import com.rechang.api.support.Fixtures;
import com.rechang.api.vo.RefundPreviewVO;
import com.rechang.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Date;
import java.util.List;

import static com.rechang.api.support.Fixtures.daysFromNow;
import static com.rechang.api.support.Fixtures.hoursAgo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退票手续费时间分档、金额计算、状态聚合与转赠单溯源。
 * 分档规则（PRD §8.3）：24h 内不可退 > 后悔期(48h内且≥7天)0% > ≥7天0% > ≥3天20% > ≥1天50%。
 */
@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    @Mock RefundRecordMapper refundRecordMapper;
    @Mock OrderMapper orderMapper;
    @Mock TicketMapper ticketMapper;
    @Mock PerformanceMapper performanceMapper;
    @Mock SeatMapper seatMapper;
    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock InvoiceService invoiceService;
    @Spy
    ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks RefundService refundService;

    private OrderEntity order;
    private Ticket ticket;
    private Performance perf;

    @BeforeEach
    void setUp() {
        order = Fixtures.order(Fixtures.ORDER_ID, Fixtures.USER_A, Fixtures.PERF_ID, "ISSUED");
        order.setPaidAt(hoursAgo(1));
        ticket = Fixtures.ticket(Fixtures.TICKET_ID, Fixtures.ORDER_ID, Fixtures.PERF_ID, "USABLE");
        perf = Fixtures.performance("ON_SALE");
        perf.setStartAt(daysFromNow(8));

        lenient().when(orderMapper.selectById(Fixtures.ORDER_ID)).thenReturn(order);
        lenient().when(ticketMapper.selectById(Fixtures.TICKET_ID)).thenReturn(ticket);
        lenient().when(performanceMapper.selectById(Fixtures.PERF_ID)).thenReturn(perf);
        // 默认：订单下仍有活跃票（部分退票场景）
        lenient().when(ticketMapper.selectCount(any())).thenReturn(1L);
        // 乐观锁冲突检查默认放行（冲突语义由专项用例验证）
        lenient().when(orderMapper.updateById(any(OrderEntity.class))).thenReturn(1);
    }

    /* ================= 时间分档矩阵（经 previewRefund 触发 calculateStage） ================= */

    private RefundPreviewVO preview() {
        return refundService.previewRefund(Fixtures.ORDER_ID, Fixtures.TICKET_ID, Fixtures.USER_A);
    }

    @Test
    @DisplayName("距开演不足 24h：不可退，优先级最高（即使处于购后悔期）")
    void stage_within24h_notRefundableEvenInRegretPeriod() {
        perf.setStartAt(daysFromNow(0.99));
        order.setPaidAt(hoursAgo(1));
        assertThat(preview().getStage()).isEqualTo("NOT_REFUNDABLE");
        assertThat(preview().getRefundable()).isFalse();
        assertThat(preview().getFeeRate()).isZero();
    }

    @Test
    @DisplayName("距开演恰好超过 24h 边界：可进入后续分档")
    void stage_justPast24h_boundaryPasses() {
        perf.setStartAt(new Date(System.currentTimeMillis() + (24 * 3600 + 600) * 1000L));
        assertThat(preview().getRefundable()).isTrue();
    }

    @Test
    @DisplayName("后悔期：支付 <48h 且距开演 ≥7天 → REGRET 0%")
    void stage_regretPeriod_zeroFee() {
        perf.setStartAt(daysFromNow(8));
        order.setPaidAt(hoursAgo(1));
        assertThat(preview().getStage()).isEqualTo("REGRET");
        assertThat(preview().getFeeRate()).isZero();
        assertThat(preview().getRefundAmount()).isEqualTo(38000);
    }

    @Test
    @DisplayName("早期：距开演 ≥7 天但已过后悔期 → EARLY 0%")
    void stage_early_zeroFee() {
        perf.setStartAt(daysFromNow(8));
        order.setPaidAt(hoursAgo(72));
        assertThat(preview().getStage()).isEqualTo("EARLY");
        assertThat(preview().getFeeRate()).isZero();
    }

    @Test
    @DisplayName("中期：3 天 ≤ 距开演 < 7 天 → MID 20%")
    void stage_mid_20percent() {
        perf.setStartAt(daysFromNow(4));
        order.setPaidAt(hoursAgo(72));
        assertThat(preview().getStage()).isEqualTo("MID");
        assertThat(preview().getFeeRate()).isEqualTo(200);
        assertThat(preview().getTicketAmount()).isEqualTo(38000);
        assertThat(preview().getFeeAmount()).isEqualTo(7600);      // 整数分运算
        assertThat(preview().getRefundAmount()).isEqualTo(30400);
    }

    @Test
    @DisplayName("晚期：1 天 ≤ 距开演 < 3 天 → LATE 50%")
    void stage_late_50percent() {
        perf.setStartAt(daysFromNow(2));
        order.setPaidAt(hoursAgo(72));
        assertThat(preview().getStage()).isEqualTo("LATE");
        assertThat(preview().getFeeRate()).isEqualTo(500);
        assertThat(preview().getFeeAmount()).isEqualTo(19000);
        assertThat(preview().getRefundAmount()).isEqualTo(19000);
    }

    @Test
    @DisplayName("整数除法边界：距开演 = 7天-ε 落入 MID（daysToPerf=6），不享后悔期费率档但依旧可退")
    void stage_integerDivisionBoundary_7dEpsilon() {
        perf.setStartAt(new Date(System.currentTimeMillis() + (7L * 24 * 3600 - 60) * 1000));
        order.setPaidAt(hoursAgo(1)); // 在后悔期内但 daysToPerf=6 < 7
        assertThat(preview().getStage()).isEqualTo("MID");
    }

    @Test
    @DisplayName("整数除法边界：距开演 = 3天+ε 命中 MID；= 3天-ε 落入 LATE")
    void stage_integerDivisionBoundary_3d() {
        perf.setStartAt(new Date(System.currentTimeMillis() + (3L * 24 * 3600 + 60) * 1000));
        order.setPaidAt(hoursAgo(72));
        assertThat(preview().getStage()).isEqualTo("MID");

        perf.setStartAt(new Date(System.currentTimeMillis() + (3L * 24 * 3600 - 60) * 1000));
        assertThat(preview().getStage()).isEqualTo("LATE");
    }

    /* ================= 校验守卫 ================= */

    @Test
    @DisplayName("订单不属于本人 → ORDER_NOT_FOUND")
    void orderNotOwned() {
        // preview 使用当前登录人 USER_B 视角
        assertThatThrownBy(() -> refundService.previewRefund(Fixtures.ORDER_ID, Fixtures.TICKET_ID, Fixtures.USER_B))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(1009);
    }

    @Test
    @DisplayName("非 ISSUED/ATTENDED 订单不可退")
    void orderStatusGuard() {
        order.setStatus("PENDING_PAY");
        assertThatThrownBy(() -> preview())
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(1012); // ORDER_STATUS_ERROR
    }

    @Test
    @DisplayName("票不属于该订单 → TICKET_NOT_FOUND")
    void ticketOrderMismatch() {
        ticket.setOrderId(9999L);
        assertThatThrownBy(() -> preview())
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(1007);
    }

    @Test
    @DisplayName("票已非 USABLE → TICKET_NOT_USABLE")
    void ticketNotUsable() {
        ticket.setStatus("REFUNDED");
        assertThatThrownBy(() -> preview())
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(1008);
    }

    /* ================= refundTicket 主流程 ================= */

    private RefundDTO dto() {
        return new RefundDTO();
    }

    @Test
    @DisplayName("中期退票成功：记录 20% 手续费、票置 REFUNDED、refunded_amount 累加")
    void refundTicket_partialKeepsOrderIssued() {
        perf.setStartAt(daysFromNow(4));
        order.setPaidAt(hoursAgo(72));
        order.setRefundedAmount(0);

        var vo = refundService.refundTicket(Fixtures.ORDER_ID, Fixtures.TICKET_ID, dto(), Fixtures.USER_A);

        ArgumentCaptor<RefundRecord> recordCap = ArgumentCaptor.forClass(RefundRecord.class);
        verify(refundRecordMapper).insert(recordCap.capture());
        RefundRecord record = recordCap.getValue();
        assertThat(record.getRefundNo()).startsWith("RF");
        assertThat(record.getRefundType()).isEqualTo("PERSONAL");
        assertThat(record.getFeeRate()).isEqualTo(200);
        assertThat(record.getFeeAmount()).isEqualTo(7600);
        assertThat(record.getRefundAmount()).isEqualTo(30400);
        assertThat(record.getStatus()).isEqualTo("SUCCESS");
        assertThat(record.getPayChannel()).isEqualTo("WECHAT");

        assertThat(ticket.getStatus()).isEqualTo("REFUNDED");
        verify(ticketMapper).updateById(ticket);

        // 活跃票数>0 → 订单保持 ISSUED，refunded_amount 累加
        ArgumentCaptor<OrderEntity> orderCap = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderMapper).updateById(orderCap.capture());
        assertThat(orderCap.getValue().getStatus()).isEqualTo("ISSUED");
        assertThat(orderCap.getValue().getRefundedAmount()).isEqualTo(30400);
        assertThat(orderCap.getValue().getRefundedAt()).isNull();

        // 部分退票不联动作废发票
        verify(invoiceService, never()).voidInvoice(any());

        assertThat(vo.getStatus()).isEqualTo("SUCCESS");
        assertThat(vo.getSeatLabel()).isEqualTo("站票"); // seatId=null
    }

    @Test
    @DisplayName("最后一张票退掉：聚合为 REFUNDED 并写 refunded_at，且联动作废发票")
    void refundTicket_lastTicketAggregatesToRefunded() {
        when(ticketMapper.selectCount(any())).thenReturn(0L);
        perf.setStartAt(daysFromNow(8));

        refundService.refundTicket(Fixtures.ORDER_ID, Fixtures.TICKET_ID, dto(), Fixtures.USER_A);

        ArgumentCaptor<OrderEntity> cap = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderMapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("REFUNDED");
        assertThat(cap.getValue().getRefundedAt()).isNotNull();
        verify(invoiceService).voidInvoice(Fixtures.ORDER_ID);
    }

    @Test
    @DisplayName("不可退档位直接拒绝退票")
    void refundTicket_rejectedInNotRefundableStage() {
        perf.setStartAt(daysFromNow(0.5));
        assertThatThrownBy(() -> refundService.refundTicket(Fixtures.ORDER_ID, Fixtures.TICKET_ID, dto(), Fixtures.USER_A))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(1016);
        verify(refundRecordMapper, never()).insert(any(RefundRecord.class));
    }

    @Test
    @DisplayName("有座票退款时删除座位 Redis 锁；无座票不动 Redis")
    void refundTicket_seatLockReleasedOnlyForSeatedTickets() {
        perf.setStartAt(daysFromNow(8));
        refundService.refundTicket(Fixtures.ORDER_ID, Fixtures.TICKET_ID, dto(), Fixtures.USER_A);
        verify(redisTemplate, never()).delete(any(String.class));

        // 第二次退款前重置票状态（首次退票已将其置为 REFUNDED）
        ticket.setStatus("USABLE");
        ticket.setSeatId(5555L);
        when(seatMapper.selectById(5555L)).thenReturn(null);
        refundService.refundTicket(Fixtures.ORDER_ID, Fixtures.TICKET_ID, dto(), Fixtures.USER_A);
        verify(redisTemplate).delete("seat:lock:" + Fixtures.PERF_ID + ":5555");
    }

    /* ================= 转赠订单溯源 ================= */

    @Test
    @DisplayName("TRANSFER 订单：经 original_pay_order_id 取真实支付单的支付时间与渠道")
    void transferOrder_resolvesOriginalPayOrder() {
        OrderEntity payOrder = Fixtures.order(88L, Fixtures.USER_A, Fixtures.PERF_ID, "TRANSFERRED");
        payOrder.setPaidAt(hoursAgo(10));
        payOrder.setPayChannel("WECHAT");

        order.setSource("TRANSFER");
        order.setTotalAmount(0);
        order.setPaidAt(null);
        order.setOriginalOrderId(77L);
        order.setOriginalPayOrderId(88L);
        when(orderMapper.selectById(88L)).thenReturn(payOrder);
        perf.setStartAt(daysFromNow(8));

        var vo = refundService.refundTicket(Fixtures.ORDER_ID, Fixtures.TICKET_ID, dto(), Fixtures.USER_A);
        assertThat(vo.getPayChannel()).isEqualTo("WECHAT");
    }

    @Test
    @DisplayName("TRANSFER 订单溯源失败（无原单）→ 无法溯源原支付订单")
    void transferOrder_traceFailed() {
        order.setSource("TRANSFER");
        order.setOriginalOrderId(null);
        order.setOriginalPayOrderId(null);

        assertThatThrownBy(() -> preview())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无法溯源原支付订单");
    }

    /* ================= 不可抗力退票 ================= */

    @Test
    @DisplayName("不可抗力：缺凭证拒绝")
    void forceMajeure_requiresEvidence() {
        assertThatThrownBy(() -> refundService.refundForceMajeure(
                        Fixtures.ORDER_ID, Fixtures.TICKET_ID, new RefundDTO(), Fixtures.USER_A))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(1017);
    }

    @Test
    @DisplayName("不可抗力：全额 0 费率，状态 PENDING，凭证序列化落库")
    void forceMajeure_success() {
        when(ticketMapper.selectCount(any())).thenReturn(0L);

        RefundDTO evidenceDto = new RefundDTO();
        evidenceDto.setEvidenceUrls(List.of("https://cdn.rechang.com/evidence/1.jpg"));

        var vo = refundService.refundForceMajeure(Fixtures.ORDER_ID, Fixtures.TICKET_ID, evidenceDto, Fixtures.USER_A);

        ArgumentCaptor<RefundRecord> cap = ArgumentCaptor.forClass(RefundRecord.class);
        verify(refundRecordMapper).insert(cap.capture());
        assertThat(cap.getValue().getRefundType()).isEqualTo("FORCE_MAJEURE");
        assertThat(cap.getValue().getFeeRate()).isZero();
        assertThat(cap.getValue().getRefundAmount()).isEqualTo(38000);
        assertThat(cap.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(cap.getValue().getEvidenceUrls()).contains("evidence/1.jpg");
        assertThat(vo.getStatus()).isEqualTo("PENDING");
        assertThat(ticket.getStatus()).isEqualTo("REFUNDED");
    }

    /* ================= 退票记录列表 ================= */

    @Test
    @DisplayName("别人的订单查询记录 → ORDER_NOT_FOUND")
    void getRefundRecords_ownerMismatch() {
        assertThatThrownBy(() -> refundService.getRefundRecords(Fixtures.ORDER_ID, Fixtures.USER_B))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(1009);
    }

    /* ================= 本人校验补充 ================= */

    @Test
    @DisplayName("本人正常预览：座位标签取自 seat 表")
    void seatLabelFromSeatTable() {
        ticket.setSeatId(42L);
        Seat seat = Fixtures.seat(42L, "A", "3", "8", "ENABLED");
        when(seatMapper.selectById(42L)).thenReturn(seat);

        assertThat(preview().getSeatLabel()).isEqualTo("3排8座");
    }
}
