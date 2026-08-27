package com.rechang.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rechang.api.entity.Attendee;
import com.rechang.api.entity.OrderEntity;
import com.rechang.api.entity.Performance;
import com.rechang.api.entity.Seat;
import com.rechang.api.entity.Ticket;
import com.rechang.api.entity.User;
import com.rechang.api.entity.Venue;
import com.rechang.api.mapper.AttendeeMapper;
import com.rechang.api.mapper.OrderMapper;
import com.rechang.api.mapper.PerformanceMapper;
import com.rechang.api.mapper.SeatMapper;
import com.rechang.api.mapper.TicketMapper;
import com.rechang.api.mapper.UserMapper;
import com.rechang.api.mapper.VenueMapper;
import com.rechang.api.vo.TransferPreviewVO;
import com.rechang.common.exception.BusinessException;
import com.rechang.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
/**
 * 转赠服务：PRD §8.5 转赠流程
 *
 * 1. 原购买者发起转赠 → 生成 24h 有效 transfer_token
 * 2. 受赠者预览 → 校验 token 有效
 * 3. 受赠者领取 → 校验实名/限购/是否已持有同场次票 → 原子操作
 *    - 原 ticket → TRANSFERRED（继续占用原购买者限购额度）
 *    - 新 ticket → USABLE（受赠者，transfer_count=1）
 *    - 新订单 → ISSUED，source=TRANSFER，total_amount=0，记录 original_order_id / original_pay_order_id
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private static final long TOKEN_TTL_HOURS = 24;
    private static final long LOCK_TTL_SECONDS = 10;
    private static final List<String> SOLD_TICKET_STATUSES = List.of("USABLE", "USED", "TRANSFERRED");

    private final TicketMapper ticketMapper;
    private final OrderMapper orderMapper;
    private final PerformanceMapper performanceMapper;
    private final VenueMapper venueMapper;
    private final SeatMapper seatMapper;
    private final UserMapper userMapper;
    private final AttendeeMapper attendeeMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 发起转赠：生成 24h 有效的 transfer_token
     * 仅原购买者可发起（owner_user_id = 当前用户），且 transfer_count = 0
     */
    @Transactional
    public TransferPreviewVO startTransfer(Long ticketId, Long userId) {
        Ticket ticket = getTransferableTicket(ticketId, userId);

        String token = UUID.randomUUID().toString().replace("-", "").toUpperCase() + System.currentTimeMillis() % 10000;
        Date expireAt = new Date(System.currentTimeMillis() + TOKEN_TTL_HOURS * 60 * 60 * 1000);

        ticket.setTransferToken(token);
        ticketMapper.updateById(ticket);

        // Redis 记录 token 与 ticket 的映射，TTL 24h（幂等防重，防并发重复领取）
        redisTemplate.opsForValue().set("transfer:token:" + token, String.valueOf(ticketId), TOKEN_TTL_HOURS, TimeUnit.HOURS);

        return buildPreview(ticket, token, expireAt);
    }

    /**
     * 转赠链接预览（受赠者打开链接时展示票信息）
     */
    public TransferPreviewVO previewTransfer(String token, Long viewerUserId) {
        Ticket ticket = resolveByToken(token);
        return buildPreview(ticket, token, null);
    }

    /**
     * 受赠者领取转赠票
     * 校验：token 有效 / 未领取 / 票 USABLE / 受赠者实名 / 受赠者未持有同场次票（限购）
     * 原子操作：原票 → TRANSFERRED + 新票 → USABLE + 新订单 → ISSUED(TRANSFER)
     */
    @Transactional
    public OrderEntity claimTransfer(String token, Long toUserId) {
        Ticket oldTicket = resolveByToken(token);

        String lockKey = "ticket:lock:" + oldTicket.getId();
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, String.valueOf(toUserId), LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(locked)) {
            throw new BusinessException(ResultCode.TRANSFER_IN_PROGRESS);
        }
        try {
            return doClaim(oldTicket, toUserId, token);
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    private OrderEntity doClaim(Ticket oldTicket, Long toUserId, String token) {
        if (!"USABLE".equals(oldTicket.getStatus())) {
            throw new BusinessException(ResultCode.TRANSFER_NOT_ALLOWED, "该票已被领取或不可转赠");
        }
        if (oldTicket.getTransferCount() != null && oldTicket.getTransferCount() >= 1) {
            throw new BusinessException(ResultCode.TRANSFER_LIMIT_EXCEEDED);
        }

        // 受赠者实名校验
        String toIdCardHash = getIdCardHash(toUserId);
        if (toIdCardHash == null) {
            throw new BusinessException(ResultCode.REALNAME_NOT_VERIFIED);
        }

        // 受赠者限购校验：同场次已持票数（USABLE/USED/TRANSFERRED）+ 1 ≤ 限购数；若已持有同场次票则拒绝
        Performance perf = performanceMapper.selectById(oldTicket.getPerformanceId());
        if (perf == null) {
            throw new BusinessException(ResultCode.PERFORMANCE_NOT_FOUND);
        }
        int limit = perf.getPurchaseLimitPerId() != null ? perf.getPurchaseLimitPerId() : 4;
        int owned = Math.toIntExact(ticketMapper.selectCount(
                new LambdaQueryWrapper<Ticket>()
                        .eq(Ticket::getAttendeeIdCardHash, toIdCardHash)
                        .eq(Ticket::getPerformanceId, oldTicket.getPerformanceId())
                        .in(Ticket::getStatus, SOLD_TICKET_STATUSES)));
        if (owned > 0) {
            throw new BusinessException(ResultCode.ALREADY_OWNED_TICKET);
        }
        if (owned + 1 > limit) {
            throw new BusinessException(ResultCode.PURCHASE_LIMIT_EXCEEDED,
                    "每场限购" + limit + "张，您已持有" + owned + "张");
        }

        OrderEntity originalOrder = orderMapper.selectById(oldTicket.getOrderId());
        if (originalOrder == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        Long originalPayOrderId = originalOrder.getOriginalPayOrderId() != null && originalOrder.getOriginalPayOrderId() > 0
                ? originalOrder.getOriginalPayOrderId()
                : originalOrder.getId();
        Date now = new Date();

        // 1. 原订单 → TRANSFERRED（终态，A 不可评价）
        originalOrder.setStatus("TRANSFERRED");
        originalOrder.setTransferredAt(now);
        originalOrder.setUpdateTime(now);
        orderMapper.updateById(originalOrder);

        // 2. 原 ticket → TRANSFERRED（继续占用 A 的限购额度）
        ticketMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Ticket>()
                        .eq(Ticket::getId, oldTicket.getId())
                        .set(Ticket::getStatus, "TRANSFERRED")
                        .set(Ticket::getTransferredAt, now)
                        .set(Ticket::getTransferToken, "")
                        .set(Ticket::getTransferCount, oldTicket.getTransferCount()));

        // 3. 生成受赠者新订单（ISSUED，来源 TRANSFER，total_amount=0）
        OrderEntity transferOrder = new OrderEntity();
        transferOrder.setOrderNo(generateOrderNo());
        transferOrder.setUserId(toUserId);
        transferOrder.setPerformanceId(oldTicket.getPerformanceId());
        transferOrder.setTotalAmount(0);
        transferOrder.setRefundedAmount(0);
        transferOrder.setPayChannel(originalOrder.getPayChannel());
        transferOrder.setSource("TRANSFER");
        transferOrder.setOriginalOrderId(oldTicket.getOrderId());
        transferOrder.setOriginalPayOrderId(originalPayOrderId);
        transferOrder.setStatus("ISSUED");
        transferOrder.setVersion(0);
        transferOrder.setCreateTime(now);
        transferOrder.setUpdateTime(now);
        orderMapper.insert(transferOrder);

        // 4. 生成受赠者新 ticket（USABLE，transfer_count=1）
        Ticket newTicket = new Ticket();
        newTicket.setOrderId(transferOrder.getId());
        newTicket.setPerformanceId(oldTicket.getPerformanceId());
        newTicket.setSeatId(oldTicket.getSeatId());
        newTicket.setFaceAmount(oldTicket.getFaceAmount());
        newTicket.setOwnerUserId(toUserId);
        newTicket.setOriginalUserId(oldTicket.getOriginalUserId());
        newTicket.setStatus("USABLE");
        newTicket.setTransferCount((oldTicket.getTransferCount() != null ? oldTicket.getTransferCount() : 0) + 1);
        newTicket.setAttendeeIdCardHash(toIdCardHash);
        newTicket.setFaceVerified(0);
        newTicket.setCreateTime(now);
        ticketMapper.insert(newTicket);

        // 5. 标记 token 已使用
        redisTemplate.delete("transfer:token:" + token);

        return transferOrder;
    }

    /**
     * 发起转赠校验：仅原购买者、票状态 USABLE、transfer_count=0
     */
    private Ticket getTransferableTicket(Long ticketId, Long userId) {
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null || !ticket.getOwnerUserId().equals(userId)) {
            throw new BusinessException(ResultCode.TRANSFER_NOT_OWNER);
        }
        if (!"USABLE".equals(ticket.getStatus())) {
            throw new BusinessException(ResultCode.TRANSFER_NOT_ALLOWED);
        }
        if (ticket.getTransferCount() != null && ticket.getTransferCount() >= 1) {
            throw new BusinessException(ResultCode.TRANSFER_LIMIT_EXCEEDED);
        }
        return ticket;
    }

    /**
     * 通过 token 解析票（token 有效 + 未领取）
     */
    private Ticket resolveByToken(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ResultCode.TRANSFER_TOKEN_INVALID);
        }
        Object ticketIdObj = redisTemplate.opsForValue().get("transfer:token:" + token);
        if (ticketIdObj == null) {
            // Redis 兜底：查 DB ticket.transfer_token（Redis 失效场景）
            Ticket dbTicket = ticketMapper.selectOne(
                    new LambdaQueryWrapper<Ticket>().eq(Ticket::getTransferToken, token));
            if (dbTicket != null && "USABLE".equals(dbTicket.getStatus())) {
                return dbTicket;
            }
            throw new BusinessException(ResultCode.TRANSFER_TOKEN_INVALID);
        }
        Long ticketId = Long.valueOf(String.valueOf(ticketIdObj));
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null || !"USABLE".equals(ticket.getStatus())) {
            throw new BusinessException(ResultCode.TRANSFER_TOKEN_INVALID);
        }
        return ticket;
    }

    private String getIdCardHash(Long userId) {
        // 优先取本人的实名 attendee（is_self=1）
        Attendee self = attendeeMapper.selectOne(
                new LambdaQueryWrapper<Attendee>()
                        .eq(Attendee::getUserId, userId)
                        .eq(Attendee::getIsSelf, 1));
        if (self != null && self.getIdCardHash() != null && !self.getIdCardHash().isBlank()) {
            return self.getIdCardHash();
        }
        return null;
    }

    private TransferPreviewVO buildPreview(Ticket ticket, String token, Date expireAt) {
        TransferPreviewVO vo = new TransferPreviewVO();
        vo.setTransferToken(token);
        vo.setPerformanceId(ticket.getPerformanceId());
        vo.setSeatLabel(resolveSeatLabel(ticket.getSeatId()));
        vo.setFaceAmount(ticket.getFaceAmount());
        vo.setExpireAt(expireAt != null ? expireAt : new Date(System.currentTimeMillis() + TOKEN_TTL_HOURS * 60 * 60 * 1000));

        Performance perf = performanceMapper.selectById(ticket.getPerformanceId());
        if (perf != null) {
            vo.setPerfName(perf.getPerfName());
            vo.setPosterUrl(perf.getPosterUrl());
            vo.setStartAt(perf.getStartAt());
            if (perf.getVenueId() != null) {
                Venue venue = venueMapper.selectById(perf.getVenueId());
                vo.setVenueName(venue != null ? venue.getVenueName() : null);
            }
        }

        User giver = userMapper.selectById(ticket.getOwnerUserId());
        if (giver != null && giver.getNickname() != null && !giver.getNickname().isBlank()) {
            vo.setGiverNickname(giver.getNickname());
        } else {
            vo.setGiverNickname("热场用户");
        }

        return vo;
    }

    private String resolveSeatLabel(Long seatId) {
        if (seatId == null) {
            return "站票";
        }
        Seat seat = seatMapper.selectById(seatId);
        if (seat == null) {
            return "站票";
        }
        return seat.getSeatLabel() != null && !seat.getSeatLabel().isBlank()
                ? seat.getSeatLabel()
                : seat.getRowLabel() + "排" + seat.getColLabel() + "座";
    }

    private String generateOrderNo() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMddHHmmss");
        return "RC" + sdf.format(new Date())
                + (int) (Math.random() * 9000 + 1000);
    }
}
