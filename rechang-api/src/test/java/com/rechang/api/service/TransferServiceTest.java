package com.rechang.api.service;

import com.rechang.api.entity.Attendee;
import com.rechang.api.entity.OrderEntity;
import com.rechang.api.entity.Performance;
import com.rechang.api.entity.Ticket;
import com.rechang.api.mapper.AttendeeMapper;
import com.rechang.api.mapper.OrderMapper;
import com.rechang.api.mapper.PerformanceMapper;
import com.rechang.api.mapper.SeatMapper;
import com.rechang.api.mapper.TicketMapper;
import com.rechang.api.mapper.UserMapper;
import com.rechang.api.mapper.VenueMapper;
import com.rechang.api.support.Fixtures;
import com.rechang.api.vo.TransferPreviewVO;
import com.rechang.common.exception.BusinessException;
import com.rechang.common.utils.HashUtils;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static com.rechang.api.support.Fixtures.attendee;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 转赠全链路（PRD §8.5 / 决策 #5/#9/#10）：
 * 发起三连校验、token 双路径解析、并发锁、受赠者实名与同场次持票、五步状态流转。
 */
@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final String TOKEN = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    @Mock TicketMapper ticketMapper;
    @Mock OrderMapper orderMapper;
    @Mock PerformanceMapper performanceMapper;
    @Mock VenueMapper venueMapper;
    @Mock SeatMapper seatMapper;
    @Mock UserMapper userMapper;
    @Mock AttendeeMapper attendeeMapper;
    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock ValueOperations<String, Object> valueOps;
    @InjectMocks TransferService transferService;

    private Ticket ticket;

    /**
     * LambdaUpdateWrapper.set() 在构造期即解析实体列名，纯单测无 Spring 容器时需手动初始化 TableInfo。
     */
    @BeforeAll
    static void initTableMeta() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Ticket.class);
    }

    @BeforeEach
    void setUp() {
        lenient().doReturn(valueOps).when(redisTemplate).opsForValue();
        ticket = Fixtures.ticket(Fixtures.TICKET_ID, Fixtures.ORDER_ID, Fixtures.PERF_ID, "USABLE");
        ticket.setOwnerUserId(Fixtures.USER_A);
        ticket.setAttendeeIdCardHash("hash-a");
        lenient().when(ticketMapper.selectById(Fixtures.TICKET_ID)).thenReturn(ticket);
        lenient().when(performanceMapper.selectById(Fixtures.PERF_ID)).thenReturn(Fixtures.performance("ON_SALE"));
    }

    /* ================= startTransfer ================= */

    @Test
    @DisplayName("发起转赠三连校验：非本人 / 非 USABLE / 已转赠过")
    void startTransferGuards() {
        assertThatThrownBy(() -> transferService.startTransfer(Fixtures.TICKET_ID, Fixtures.USER_B))
                .matches(e -> ((BusinessException) e).getCode() == 1025); // NOT_OWNER

        ticket.setStatus("USED");
        assertThatThrownBy(() -> transferService.startTransfer(Fixtures.TICKET_ID, Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 1026); // NOT_ALLOWED

        ticket.setStatus("USABLE");
        ticket.setTransferCount(1);
        assertThatThrownBy(() -> transferService.startTransfer(Fixtures.TICKET_ID, Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 1027); // LIMIT_EXCEEDED
    }

    @Test
    @DisplayName("发起成功：写回 transfer_token、Redis 24h 映射、预览默认兜底昵称")
    void startTransferSuccess() {
        var giver = new com.rechang.api.entity.User();
        giver.setId(Fixtures.USER_A);
        giver.setNickname("");
        lenient().when(userMapper.selectById(Fixtures.USER_A)).thenReturn(giver);

        TransferPreviewVO vo = transferService.startTransfer(Fixtures.TICKET_ID, Fixtures.USER_A);

        assertThat(ticket.getTransferToken()).isNotBlank();
        assertThat(ticket.getTransferToken()).isEqualTo(vo.getTransferToken());
        verify(ticketMapper).updateById(ticket);

        verify(valueOps).set(eq("transfer:token:" + vo.getTransferToken()), eq(String.valueOf(Fixtures.TICKET_ID)),
                eq(24L), eq(TimeUnit.HOURS));

        assertThat(vo.getGiverNickname()).isEqualTo("热场用户");
        assertThat(vo.getFaceAmount()).isEqualTo(38000);
        // 预览过期时间 ≈ now + 24h
        long delta = vo.getExpireAt().getTime() - System.currentTimeMillis();
        assertThat(delta).isBetween(23L * 3600 * 1000, 24L * 3600 * 1000 + 5000);
    }

    /* ================= token 解析 ================= */

    @Test
    @DisplayName("token 为空直接无效")
    void blankTokenInvalid() {
        assertThatThrownBy(() -> transferService.previewTransfer(" ", Fixtures.USER_B))
                .matches(e -> ((BusinessException) e).getCode() == 1024);
    }

    @Test
    @DisplayName("Redis 未命中 → DB transfer_token 兜底；兜底也失败 → INVALID")
    void tokenDbFallback() {
        when(valueOps.get("transfer:token:" + TOKEN)).thenReturn(null);
        when(ticketMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> transferService.previewTransfer(TOKEN, Fixtures.USER_B))
                .matches(e -> ((BusinessException) e).getCode() == 1024);

        Ticket used = Fixtures.ticket(2L, 20L, Fixtures.PERF_ID, "TRANSFERRED");
        when(ticketMapper.selectOne(any())).thenReturn(used);
        assertThatThrownBy(() -> transferService.previewTransfer(TOKEN, Fixtures.USER_B))
                .matches(e -> ((BusinessException) e).getCode() == 1024);
    }

    @Test
    @DisplayName("Redis 命中但票已非 USABLE → INVALID")
    void tokenHitButTicketNotUsable() {
        when(valueOps.get("transfer:token:" + TOKEN)).thenReturn("1000");
        ticket.setStatus("TRANSFERRED");
        assertThatThrownBy(() -> transferService.claimTransfer(TOKEN, Fixtures.USER_B))
                .matches(e -> ((BusinessException) e).getCode() == 1024);
    }

    /* ================= claimTransfer ================= */

    private void resolveThroughRedis() {
        when(valueOps.get("transfer:token:" + TOKEN)).thenReturn(String.valueOf(Fixtures.TICKET_ID));
        lenient().when(attendeeMapper.selectOne(any())).thenReturn(
                attendee(11L, Fixtures.USER_B, "李四", "hash-b", 1));
        lenient().when(orderMapper.selectById(Fixtures.ORDER_ID)).thenReturn(
                Fixtures.order(Fixtures.ORDER_ID, Fixtures.USER_A, Fixtures.PERF_ID, "ISSUED"));
    }

    @Test
    @DisplayName("并发锁被占用 → TRANSFER_IN_PROGRESS")
    void claimLocked() {
        resolveThroughRedis();
        when(valueOps.setIfAbsent(anyString(), anyString(), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(false);
        assertThatThrownBy(() -> transferService.claimTransfer(TOKEN, Fixtures.USER_B))
                .matches(e -> ((BusinessException) e).getCode() == 1029);
    }

    @Test
    @DisplayName("受赠者未实名（无 is_self 观演人）→ REALNAME_NOT_VERIFIED")
    void claimRequiresRealname() {
        resolveThroughRedis();
        when(valueOps.setIfAbsent(anyString(), anyString(), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(attendeeMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> transferService.claimTransfer(TOKEN, Fixtures.USER_B))
                .matches(e -> ((BusinessException) e).getCode() == 1002);
    }

    @Test
    @DisplayName("受赠者已持有同场次票 → ALREADY_OWNED_TICKET")
    void claimRejectsAlreadyOwned() {
        resolveThroughRedis();
        when(valueOps.setIfAbsent(anyString(), anyString(), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(ticketMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> transferService.claimTransfer(TOKEN, Fixtures.USER_B))
                .matches(e -> ((BusinessException) e).getCode() == 1028);
    }

    @Test
    @DisplayName("票在 token 有效期内已被领取（状态非 USABLE）→ resolveByToken 直接判定 TOKEN_INVALID")
    void claimOldTicketAlreadyTaken() {
        when(valueOps.get("transfer:token:" + TOKEN)).thenReturn(String.valueOf(Fixtures.TICKET_ID));
        ticket.setStatus("TRANSFERRED");
        assertThatThrownBy(() -> transferService.claimTransfer(TOKEN, Fixtures.USER_B))
                .matches(e -> ((BusinessException) e).getCode() == 1024);
    }

    @Test
    @DisplayName("transfer_count ≥ 1 的票不可再领（每张仅可转赠一次）")
    void claimTransferCountLimit() {
        when(valueOps.get("transfer:token:" + TOKEN)).thenReturn(String.valueOf(Fixtures.TICKET_ID));
        ticket.setTransferCount(1);
        when(valueOps.setIfAbsent(anyString(), anyString(), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(true);
        assertThatThrownBy(() -> transferService.claimTransfer(TOKEN, Fixtures.USER_B))
                .matches(e -> ((BusinessException) e).getCode() == 1027);
    }

    @Test
    @DisplayName("原订单不存在 → ORDER_NOT_FOUND")
    void claimOriginalOrderMissing() {
        resolveThroughRedis();
        when(valueOps.setIfAbsent(anyString(), anyString(), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(ticketMapper.selectCount(any())).thenReturn(0L);
        when(orderMapper.selectById(Fixtures.ORDER_ID)).thenReturn(null);
        assertThatThrownBy(() -> transferService.claimTransfer(TOKEN, Fixtures.USER_B))
                .matches(e -> ((BusinessException) e).getCode() == 1009);
    }

    @Test
    @DisplayName("领取成功五步流转：原单 TRANSFERRED / 原票置 TRANSFERRED / 新单 TRANSFER·0元·溯源 / 新票 count+1 / token 消费")
    void claimSuccessFiveSteps() {
        resolveThroughRedis();
        when(valueOps.setIfAbsent(anyString(), anyString(), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(ticketMapper.selectCount(any())).thenReturn(0L);

        OrderEntity originalOrder = Fixtures.order(Fixtures.ORDER_ID, Fixtures.USER_A, Fixtures.PERF_ID, "ISSUED");
        originalOrder.setOriginalPayOrderId(88L);
        when(orderMapper.selectById(Fixtures.ORDER_ID)).thenReturn(originalOrder);

        // 新订单 insert 分配主键（真实场景 AUTO_INCREMENT，NOT NULL ticket.order_id 依赖它）
        when(orderMapper.insert(any(OrderEntity.class))).thenAnswer(inv -> {
            inv.getArgument(0, OrderEntity.class).setId(7777L);
            return 1;
        });
        when(ticketMapper.insert(any(Ticket.class))).thenReturn(1);

        OrderEntity claimed = transferService.claimTransfer(TOKEN, Fixtures.USER_B);

        // 1. 原订单
        assertThat(originalOrder.getStatus()).isEqualTo("TRANSFERRED");
        assertThat(originalOrder.getTransferredAt()).isNotNull();
        verify(orderMapper).updateById(originalOrder);

        // 2. 原 ticket 批量置 TRANSFERRED
        verify(ticketMapper).update(eq(null), any());

        // 3. 新订单（受赠者视角）
        ArgumentCaptor<OrderEntity> orderCap = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderMapper).insert(orderCap.capture());
        OrderEntity transferOrder = orderCap.getValue();
        assertThat(transferOrder.getSource()).isEqualTo("TRANSFER");
        assertThat(transferOrder.getTotalAmount()).isZero();
        assertThat(transferOrder.getStatus()).isEqualTo("ISSUED");
        assertThat(transferOrder.getOriginalOrderId()).isEqualTo(Fixtures.ORDER_ID);
        assertThat(transferOrder.getOriginalPayOrderId()).isEqualTo(88L); // 溯源退款用
        assertThat(transferOrder.getPayChannel()).isEqualTo("WECHAT");
        assertThat(claimed).isSameAs(transferOrder);

        // 4. 新票
        ArgumentCaptor<Ticket> ticketCap = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketMapper).insert(ticketCap.capture());
        Ticket newTicket = ticketCap.getValue();
        assertThat(newTicket.getOrderId()).isEqualTo(7777L);           // 先建单后建票并回填
        assertThat(newTicket.getOwnerUserId()).isEqualTo(Fixtures.USER_B);
        assertThat(newTicket.getStatus()).isEqualTo("USABLE");
        assertThat(newTicket.getTransferCount()).isEqualTo(1);          // 原票 0 → 受赠票 1
        assertThat(newTicket.getAttendeeIdCardHash()).isEqualTo("hash-b");
        assertThat(newTicket.getSeatId()).isEqualTo(ticket.getSeatId()); // 座位继承

        // 5. token 消费 + 领取锁释放（finally）
        verify(redisTemplate).delete("transfer:token:" + TOKEN);
        verify(redisTemplate).delete("ticket:lock:" + Fixtures.TICKET_ID);
    }

    @Test
    @DisplayName("原订单无 original_pay_order_id 时回退 original_order_id=自身")
    void claimFallbackOriginalPayOrder() {
        resolveThroughRedis();
        when(valueOps.setIfAbsent(anyString(), anyString(), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(ticketMapper.selectCount(any())).thenReturn(0L);
        OrderEntity originalOrder = Fixtures.order(Fixtures.ORDER_ID, Fixtures.USER_A, Fixtures.PERF_ID, "ISSUED");
        originalOrder.setOriginalPayOrderId(null);
        when(orderMapper.selectById(Fixtures.ORDER_ID)).thenReturn(originalOrder);
        when(orderMapper.insert(any(OrderEntity.class))).thenReturn(1);
        when(ticketMapper.insert(any(Ticket.class))).thenReturn(1);

        transferService.claimTransfer(TOKEN, Fixtures.USER_B);

        ArgumentCaptor<OrderEntity> cap = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderMapper).insert(cap.capture());
        assertThat(cap.getValue().getOriginalPayOrderId()).isEqualTo(Fixtures.ORDER_ID);
    }

    @Test
    @DisplayName("实名 hash 取自受赠者 is_self 观演人")
    void claimUsesSelfAttendeeHash() {
        resolveThroughRedis();
        when(valueOps.setIfAbsent(anyString(), anyString(), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(ticketMapper.selectCount(any())).thenReturn(0L);
        Attendee self = attendee(11L, Fixtures.USER_B, "李四", HashUtils.sha256("330102199001010012"), 1);
        when(attendeeMapper.selectOne(any())).thenReturn(self);
        when(orderMapper.insert(any(OrderEntity.class))).thenReturn(1);
        when(ticketMapper.insert(any(Ticket.class))).thenReturn(1);

        transferService.claimTransfer(TOKEN, Fixtures.USER_B);

        ArgumentCaptor<Ticket> cap = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketMapper).insert(cap.capture());
        assertThat(cap.getValue().getAttendeeIdCardHash()).isEqualTo(HashUtils.sha256("330102199001010012"));
    }

    @Test
    @DisplayName("previewTransfer 展示演出/场馆/出让人信息")
    void previewInfo() {
        when(valueOps.get("transfer:token:" + TOKEN)).thenReturn(String.valueOf(Fixtures.TICKET_ID));
        var giver = new com.rechang.api.entity.User();
        giver.setId(Fixtures.USER_A);
        giver.setNickname("阿黄");
        when(userMapper.selectById(Fixtures.USER_A)).thenReturn(giver);

        TransferPreviewVO vo = transferService.previewTransfer(TOKEN, Fixtures.USER_B);
        assertThat(vo.getPerfName()).isEqualTo("周杰伦嘉年华");
        assertThat(vo.getGiverNickname()).isEqualTo("阿黄");
        verify(seatMapper, never()).selectById(any());
    }
}
