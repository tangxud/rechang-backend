package com.rechang.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rechang.api.entity.*;
import com.rechang.api.mapper.*;
import com.rechang.api.vo.TicketVO;
import com.rechang.common.exception.BusinessException;
import com.rechang.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TicketService {

    /** 检票二维码 HMAC 密钥：dev 默认值仅供本地开发与单测，各环境经 qr.hmac-secret 由 AppSecretConfig 注入 */
    private static volatile String hmacSecret = "rechang-qr-code-hmac-secret-key-2026";
    private static final long QR_EXPIRE_SECONDS = 30;
    private static final long QR_VALID_WINDOW_MS = 5L * 60 * 1000;

    public static void initHmacSecret(String secret) {
        if (secret != null && !secret.isBlank()) {
            hmacSecret = secret;
        }
    }

    private final TicketMapper ticketMapper;
    private final PerformanceMapper performanceMapper;
    private final VenueMapper venueMapper;
    private final SeatMapper seatMapper;
    private final PerformancePriceZoneMapper performancePriceZoneMapper;
    private final AttendeeMapper attendeeMapper;
    private final OrderMapper orderMapper;
    private final PerformanceReviewMapper performanceReviewMapper;

    public List<TicketVO> getTicketList(Long userId, String status) {
        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getOwnerUserId, userId)
                .orderByDesc(Ticket::getCreateTime);
        if (status != null && !status.isBlank()) {
            wrapper.eq(Ticket::getStatus, status);
        }
        List<Ticket> tickets = ticketMapper.selectList(wrapper);
        if (tickets.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> performanceIds = tickets.stream().map(Ticket::getPerformanceId).collect(Collectors.toSet());
        Set<Long> seatIds = tickets.stream().map(Ticket::getSeatId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> cardHashes = tickets.stream().map(Ticket::getAttendeeIdCardHash).filter(Objects::nonNull).collect(Collectors.toSet());

        Map<Long, Performance> performanceMap = performanceMapper.selectBatchIds(performanceIds)
                .stream().collect(Collectors.toMap(Performance::getId, p -> p));

        Set<Long> venueIds = performanceMap.values().stream().map(Performance::getVenueId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Venue> venueMap = venueIds.isEmpty() ? Collections.emptyMap()
                : venueMapper.selectBatchIds(venueIds).stream().collect(Collectors.toMap(Venue::getId, v -> v));

        Map<Long, Seat> seatMap = seatIds.isEmpty() ? Collections.emptyMap()
                : seatMapper.selectBatchIds(seatIds).stream().collect(Collectors.toMap(Seat::getId, s -> s));

        Map<Long, List<PerformancePriceZone>> zoneMap = performancePriceZoneMapper.selectList(
                        new LambdaQueryWrapper<PerformancePriceZone>().in(PerformancePriceZone::getPerformanceId, performanceIds))
                .stream().collect(Collectors.groupingBy(PerformancePriceZone::getPerformanceId));

        Map<String, Attendee> attendeeMap = cardHashes.isEmpty() ? Collections.emptyMap()
                : attendeeMapper.selectList(
                        new LambdaQueryWrapper<Attendee>()
                                .eq(Attendee::getUserId, userId)
                                .in(Attendee::getIdCardHash, cardHashes))
                .stream().collect(Collectors.toMap(Attendee::getIdCardHash, a -> a, (a1, a2) -> a1));

        Set<Long> orderIds = tickets.stream().map(Ticket::getOrderId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, OrderEntity> orderMap = orderIds.isEmpty() ? Collections.emptyMap()
                : orderMapper.selectBatchIds(orderIds).stream().collect(Collectors.toMap(OrderEntity::getId, o -> o));

        Set<Long> reviewedOrderIds = orderMap.values().stream()
                .filter(o -> "REVIEWED".equals(o.getStatus()))
                .map(OrderEntity::getId).collect(Collectors.toSet());
        Map<Long, Long> reviewOrderIdMap;
        if (reviewedOrderIds.isEmpty()) {
            reviewOrderIdMap = Collections.emptyMap();
        } else {
            reviewOrderIdMap = performanceReviewMapper.selectList(
                            new LambdaQueryWrapper<PerformanceReview>()
                                    .in(PerformanceReview::getOrderId, reviewedOrderIds))
                    .stream().collect(Collectors.toMap(PerformanceReview::getOrderId, PerformanceReview::getId));
        }

        return tickets.stream().map(ticket -> toVO(ticket, performanceMap, venueMap, seatMap, zoneMap, attendeeMap, orderMap, reviewOrderIdMap)).toList();
    }

    public Map<String, Object> getQrCode(Long ticketId, Long userId) {
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null || !ticket.getOwnerUserId().equals(userId)) {
            throw new BusinessException(ResultCode.TICKET_NOT_FOUND);
        }
        if (!"USABLE".equals(ticket.getStatus())) {
            throw new BusinessException(ResultCode.TICKET_NOT_USABLE);
        }

        long expireAt = System.currentTimeMillis() + QR_EXPIRE_SECONDS * 1000;
        String qrContent = "ticket:" + ticketId + ":" + expireAt;
        String signature = hmacSha256(qrContent);

        Map<String, Object> result = new HashMap<>();
        result.put("qrContent", qrContent);
        result.put("expireAt", new Date(expireAt));
        result.put("signature", signature);
        return result;
    }

    /**
     * 闸机核销（PRD §8.6）
     * 1. 闸机扫码 → 读取 qrContent（ticket:{id}:{expireAt}）+ 签名
     * 2. 校验签名（HMAC-SHA256）→ 校验时间戳（5 分钟有效期）→ 校验票状态 USABLE
     * 3. 强实名：人脸核验通过
     * 4. ticket.status = USED, used_at = now()
     * 5. 订单状态流转：order.status = ATTENDED, completed_at = used_at（首张核销写入）
     * 6. 一证一票：同身份证同场次已核销过则拒绝
     */
    @Transactional
    public Map<String, Object> verifyTicket(Long ticketId, String qrContent, String signature, String faceVerifyResult) {
        // 1. 解析 qrContent 格式 ticket:{id}:{expireAt}
        if (qrContent == null || !qrContent.startsWith("ticket:")) {
            throw new BusinessException(ResultCode.QR_INVALID);
        }
        String[] parts = qrContent.split(":");
        if (parts.length != 3) {
            throw new BusinessException(ResultCode.QR_INVALID);
        }
        Long qrTicketId;
        long expireAt;
        try {
            qrTicketId = Long.parseLong(parts[1]);
            expireAt = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            throw new BusinessException(ResultCode.QR_INVALID);
        }
        if (!qrTicketId.equals(ticketId)) {
            throw new BusinessException(ResultCode.QR_INVALID);
        }

        // 2. 校验签名（HMAC-SHA256，防篡改）
        if (signature == null || !hmacSha256(qrContent).equals(signature)) {
            throw new BusinessException(ResultCode.QR_SIGNATURE_MISMATCH);
        }

        // 3. 校验时间戳（5 分钟有效期）
        long now = System.currentTimeMillis();
        if (expireAt < now - QR_VALID_WINDOW_MS) {
            throw new BusinessException(ResultCode.QR_INVALID);
        }

        // 4. 校验票状态 USABLE
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new BusinessException(ResultCode.TICKET_NOT_FOUND);
        }
        if ("USED".equals(ticket.getStatus())) {
            throw new BusinessException(ResultCode.TICKET_ALREADY_USED);
        }
        if (!"USABLE".equals(ticket.getStatus())) {
            throw new BusinessException(ResultCode.TICKET_NOT_USABLE);
        }

        // 5. 一证一票：同身份证同场次已核销过则拒绝
        if (ticket.getAttendeeIdCardHash() != null && !ticket.getAttendeeIdCardHash().isBlank()) {
            Long usedCount = ticketMapper.selectCount(
                    new LambdaQueryWrapper<Ticket>()
                            .eq(Ticket::getAttendeeIdCardHash, ticket.getAttendeeIdCardHash())
                            .eq(Ticket::getPerformanceId, ticket.getPerformanceId())
                            .eq(Ticket::getStatus, "USED"));
            if (usedCount > 0) {
                throw new BusinessException(ResultCode.TICKET_ID_CARD_USED);
            }
        }

        // 6. 人脸核验（强实名）：faceVerifyResult 为核验通过 token
        if (faceVerifyResult == null || faceVerifyResult.isBlank()) {
            throw new BusinessException(ResultCode.FACE_VERIFY_FAILED);
        }

        // 7. 核销成功：ticket.status = USED, used_at = now, face_verified = 1
        Date usedAt = new Date(now);
        Ticket update = new Ticket();
        update.setId(ticketId);
        update.setStatus("USED");
        update.setUsedAt(usedAt);
        update.setFaceVerified(1);
        ticketMapper.updateById(update);

        // 8. 订单状态流转：order.status = ATTENDED, completed_at = used_at（首张核销写入）
        Long orderId = ticket.getOrderId();
        if (orderId != null) {
            OrderEntity order = orderMapper.selectById(orderId);
            if (order != null && !"ATTENDED".equals(order.getStatus()) && !"REVIEWED".equals(order.getStatus())) {
                OrderEntity orderUpdate = new OrderEntity();
                orderUpdate.setId(orderId);
                orderUpdate.setStatus("ATTENDED");
                orderUpdate.setCompletedAt(usedAt);
                orderUpdate.setUpdateTime(usedAt);
                orderMapper.updateById(orderUpdate);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("ticketId", ticketId);
        result.put("seatLabel", resolveSeatLabel(ticket.getSeatId(), Collections.emptyMap()));
        result.put("usedAt", usedAt);
        return result;
    }

    private TicketVO toVO(Ticket ticket, Map<Long, Performance> performanceMap, Map<Long, Venue> venueMap,
                           Map<Long, Seat> seatMap, Map<Long, List<PerformancePriceZone>> zoneMap,
                           Map<String, Attendee> attendeeMap,
                           Map<Long, OrderEntity> orderMap, Map<Long, Long> reviewOrderIdMap) {
        TicketVO vo = new TicketVO();
        vo.setId(ticket.getId());
        vo.setOrderId(ticket.getOrderId());
        vo.setPerformanceId(ticket.getPerformanceId());
        vo.setSeatId(ticket.getSeatId());
        vo.setFaceAmount(ticket.getFaceAmount());
        vo.setOwnerUserId(ticket.getOwnerUserId());
        vo.setStatus(ticket.getStatus());
        vo.setTransferCount(ticket.getTransferCount());
        vo.setUsedAt(ticket.getUsedAt());

        vo.setSeatLabel(resolveSeatLabel(ticket.getSeatId(), seatMap));

        Attendee attendee = attendeeMap.get(ticket.getAttendeeIdCardHash());
        if (attendee != null) {
            vo.setAttendeeIdCardMasked(attendee.getIdCardMasked());
        } else {
            vo.setAttendeeIdCardMasked("****");
        }

        Performance perf = performanceMap.get(ticket.getPerformanceId());
        if (perf != null) {
            vo.setPerformanceName(perf.getPerfName());
            vo.setPosterUrl(perf.getPosterUrl());
            vo.setStartAt(perf.getStartAt());
            vo.setEndAt(perf.getEndAt());

            Venue venue = venueMap.get(perf.getVenueId());
            if (venue != null) {
                vo.setVenueName(venue.getVenueName());
            }

            Seat seat = ticket.getSeatId() != null ? seatMap.get(ticket.getSeatId()) : null;
            if (seat != null) {
                List<PerformancePriceZone> zones = zoneMap.get(ticket.getPerformanceId());
                if (zones != null) {
                    zones.stream()
                            .filter(z -> z.getRegion().equals(seat.getRegion()))
                            .findFirst()
                            .ifPresent(z -> vo.setZoneName(z.getZoneName()));
                }
            }
        }

        OrderEntity order = ticket.getOrderId() != null ? orderMap.get(ticket.getOrderId()) : null;
        if (order != null) {
            vo.setOrderStatus(order.getStatus());
        }
        if (ticket.getOrderId() != null && reviewOrderIdMap.containsKey(ticket.getOrderId())) {
            vo.setReviewId(reviewOrderIdMap.get(ticket.getOrderId()));
        }

        return vo;
    }

    private String resolveSeatLabel(Long seatId, Map<Long, Seat> seatMap) {
        if (seatId == null) {
            return "站票";
        }
        Seat seat = seatMap.get(seatId);
        if (seat == null) {
            return "站票";
        }
        return seat.getSeatLabel() != null && !seat.getSeatLabel().isBlank()
                ? seat.getSeatLabel()
                : seat.getRowLabel() + "排" + seat.getColLabel() + "座";
    }

    private String hmacSha256(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "二维码生成失败");
        }
    }
}
