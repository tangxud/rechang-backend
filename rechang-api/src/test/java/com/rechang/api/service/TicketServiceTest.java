package com.rechang.api.service;

import com.rechang.api.entity.OrderEntity;
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
import com.rechang.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检票二维码生成与闸机核销 13 步守卫链（PRD §8.6）。
 * 单测进程未启动 AppSecretConfig，TicketService 使用内置 dev HMAC 密钥——测试内以同一密钥复现签名。
 */
@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    private static final String DEV_HMAC_SECRET = "rechang-qr-code-hmac-secret-key-2026";

    @Mock TicketMapper ticketMapper;
    @Mock PerformanceMapper performanceMapper;
    @Mock VenueMapper venueMapper;
    @Mock SeatMapper seatMapper;
    @Mock PerformancePriceZoneMapper performancePriceZoneMapper;
    @Mock AttendeeMapper attendeeMapper;
    @Mock OrderMapper orderMapper;
    @Mock PerformanceReviewMapper performanceReviewMapper;
    @InjectMocks TicketService ticketService;

    private Ticket ticket;

    @BeforeEach
    void setUp() {
        ticket = Fixtures.ticket(Fixtures.TICKET_ID, Fixtures.ORDER_ID, Fixtures.PERF_ID, "USABLE");
        lenient().when(ticketMapper.selectById(Fixtures.TICKET_ID)).thenReturn(ticket);
        // 乐观锁冲突检查默认放行（冲突语义由专项用例验证）
        lenient().when(orderMapper.updateById(any(OrderEntity.class))).thenReturn(1);
    }

    /* ---- 测试内复现服务的 HMAC-SHA256 签名 ---- */
    private String sign(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(DEV_HMAC_SECRET.getBytes("UTF-8"), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(content.getBytes("UTF-8")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String qrContent(long expireAt) {
        return "ticket:" + Fixtures.TICKET_ID + ":" + expireAt;
    }

    /* ================= getQrCode ================= */

    @Test
    @DisplayName("非持有人取二维码 → TICKET_NOT_FOUND")
    void qrNotOwner() {
        assertThatThrownBy(() -> ticketService.getQrCode(Fixtures.TICKET_ID, Fixtures.USER_B))
                .matches(e -> ((BusinessException) e).getCode() == 1007);
    }

    @Test
    @DisplayName("非 USABLE 票不可取二维码")
    void qrNotUsable() {
        ticket.setStatus("USED");
        assertThatThrownBy(() -> ticketService.getQrCode(Fixtures.TICKET_ID, Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 1008);
    }

    @Test
    @DisplayName("二维码内容格式 ticket:{id}:{+30s}，签名可被闸机侧同密钥验证")
    @SuppressWarnings("unchecked")
    void qrContentFormatAndSignature() {
        Map<String, Object> result = (Map<String, Object>) (Map<?, ?>) ticketService.getQrCode(Fixtures.TICKET_ID, Fixtures.USER_A);
        String content = (String) result.get("qrContent");
        assertThat(content).matches("^ticket:" + Fixtures.TICKET_ID + ":\\d+$");
        long expireAt = Long.parseLong(content.split(":")[2]);
        assertThat(expireAt).isBetween(
                System.currentTimeMillis() + 25_000,
                System.currentTimeMillis() + 30_000);
        assertThat(sign(content)).isEqualTo(result.get("signature"));
    }

    /* ================= verifyTicket 守卫链 ================= */

    private Long doVerify(String content, String signature, String face) {
        var result = ticketService.verifyTicket(Fixtures.TICKET_ID, content, signature, face);
        return (Long) result.get("ticketId");
    }

    @Test
    @DisplayName("格式守卫：非 ticket: 前缀 / 段数≠3 / 非数字 / 票号不符 → QR_INVALID")
    void malformedQrContents() {
        String ok = qrContent(System.currentTimeMillis() + 10_000);
        assertThatThrownBy(() -> doVerify("order:1:2", sign(ok), "face")).matches(e -> ((BusinessException) e).getCode() == 1030);
        assertThatThrownBy(() -> doVerify("ticket:1", sign(ok), "face")).matches(e -> ((BusinessException) e).getCode() == 1030);
        assertThatThrownBy(() -> doVerify("ticket:x:abc", sign(ok), "face")).matches(e -> ((BusinessException) e).getCode() == 1030);
        assertThatThrownBy(() -> doVerify("ticket:999:123", sign(ok), "face")).matches(e -> ((BusinessException) e).getCode() == 1030);
    }

    @Test
    @DisplayName("签名不匹配 → QR_SIGNATURE_MISMATCH")
    void signatureMismatch() {
        String content = qrContent(System.currentTimeMillis() + 10_000);
        assertThatThrownBy(() -> doVerify(content, "bad-signature", "face"))
                .matches(e -> ((BusinessException) e).getCode() == 1031);
    }

    @Test
    @DisplayName("时间窗：过期超 5 分钟宽限 → QR_INVALID；宽限期内仍可核销")
    void expiryWindow() {
        String expired = qrContent(System.currentTimeMillis() - 6 * 60 * 1000);
        assertThatThrownBy(() -> doVerify(expired, sign(expired), "face"))
                .matches(e -> ((BusinessException) e).getCode() == 1030);

        String inGrace = qrContent(System.currentTimeMillis() - 60 * 1000);
        assertThat(doVerify(inGrace, sign(inGrace), "face")).isEqualTo(Fixtures.TICKET_ID);
    }

    @Test
    @DisplayName("票不存在 / 已核销 / 非 USABLE 三种票态拒绝")
    void ticketStateGuards() {
        String content = qrContent(System.currentTimeMillis() + 10_000);
        String sig = sign(content);

        when(ticketMapper.selectById(Fixtures.TICKET_ID)).thenReturn(null);
        assertThatThrownBy(() -> doVerify(content, sig, "face")).matches(e -> ((BusinessException) e).getCode() == 1007);

        when(ticketMapper.selectById(Fixtures.TICKET_ID)).thenReturn(ticket);
        ticket.setStatus("USED");
        assertThatThrownBy(() -> doVerify(content, sig, "face")).matches(e -> ((BusinessException) e).getCode() == 1033);

        ticket.setStatus("REFUNDED");
        assertThatThrownBy(() -> doVerify(content, sig, "face")).matches(e -> ((BusinessException) e).getCode() == 1008);
    }

    @Test
    @DisplayName("一证一票：同身份证同场次已核销过 → TICKET_ID_CARD_USED")
    void oneIdCardOneTicketPerShow() {
        ticket.setAttendeeIdCardHash("hash-a");
        String content = qrContent(System.currentTimeMillis() + 10_000);
        when(ticketMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> doVerify(content, sign(content), "face"))
                .matches(e -> ((BusinessException) e).getCode() == 1034);
    }

    @Test
    @DisplayName("强实名：人脸核验结果为空 → FACE_VERIFY_FAILED")
    void faceVerifyRequired() {
        String content = qrContent(System.currentTimeMillis() + 10_000);
        assertThatThrownBy(() -> doVerify(content, sign(content), ""))
                .matches(e -> ((BusinessException) e).getCode() == 1032);
        verify(orderMapper, never()).updateById(any(OrderEntity.class));
    }

    @Test
    @DisplayName("核销成功：更新实体带 USED+face_verified，订单 ISSUED→ATTENDED 并写 completed_at")
    void verifySuccessFlow() {
        String content = qrContent(System.currentTimeMillis() + 10_000);
        ticket.setAttendeeIdCardHash("");   // 无实名 hash → 跳过一证一票
        OrderEntity order = Fixtures.order(Fixtures.ORDER_ID, Fixtures.USER_A, Fixtures.PERF_ID, "ISSUED");
        when(orderMapper.selectById(Fixtures.ORDER_ID)).thenReturn(order);

        Map<String, Object> result = ticketService.verifyTicket(Fixtures.TICKET_ID, content, sign(content), "face-token-ok");

        assertThat(result.get("ticketId")).isEqualTo(Fixtures.TICKET_ID);
        assertThat(result.get("seatLabel")).isEqualTo("站票");

        // 服务不改原查询对象，核销字段断言落在 updateById 的载体上
        ArgumentCaptor<Ticket> cap = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketMapper).updateById(cap.capture());
        assertThat(cap.getValue().getId()).isEqualTo(Fixtures.TICKET_ID);
        assertThat(cap.getValue().getStatus()).isEqualTo("USED");
        assertThat(cap.getValue().getFaceVerified()).isEqualTo(1);
        assertThat(cap.getValue().getUsedAt()).isNotNull();

        // 订单流转更新完整加载的实体（乐观锁 version 条件生效，票 #30004）
        ArgumentCaptor<OrderEntity> orderCap = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderMapper).updateById(orderCap.capture());
        assertThat(orderCap.getValue().getId()).isEqualTo(Fixtures.ORDER_ID);
        assertThat(orderCap.getValue().getStatus()).isEqualTo("ATTENDED");
        assertThat(orderCap.getValue().getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("订单已是 ATTENDED/REVIEWED 时不重复流转（completed_at 保持首张核销时间）")
    void verifyDoesNotOverwriteOrderState() {
        String content = qrContent(System.currentTimeMillis() + 10_000);
        OrderEntity attended = Fixtures.order(Fixtures.ORDER_ID, Fixtures.USER_A, Fixtures.PERF_ID, "ATTENDED");
        attended.setCompletedAt(new java.util.Date(0));
        when(orderMapper.selectById(Fixtures.ORDER_ID)).thenReturn(attended);

        ticketService.verifyTicket(Fixtures.TICKET_ID, content, sign(content), "face-token-ok");

        assertThat(attended.getCompletedAt()).isEqualTo(new java.util.Date(0));
        verify(orderMapper, never()).updateById(any(OrderEntity.class));
    }

    @Test
    @DisplayName("核销更新载体只包含核销相关字段（updateById 专用实体）")
    void verifyUpdatePayload() {
        String content = qrContent(System.currentTimeMillis() + 10_000);
        OrderEntity order = Fixtures.order(Fixtures.ORDER_ID, Fixtures.USER_A, Fixtures.PERF_ID, "ISSUED");
        when(orderMapper.selectById(Fixtures.ORDER_ID)).thenReturn(order);

        ticketService.verifyTicket(Fixtures.TICKET_ID, content, sign(content), "face-token-ok");

        ArgumentCaptor<Ticket> cap = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketMapper).updateById(cap.capture());
        assertThat(cap.getValue().getId()).isEqualTo(Fixtures.TICKET_ID);
        assertThat(cap.getValue().getStatus()).isEqualTo("USED");
    }
}
