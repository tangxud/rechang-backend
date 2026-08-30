package com.rechang.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rechang.api.dto.CreateOrderDTO;
import com.rechang.api.entity.Attendee;
import com.rechang.api.entity.OrderEntity;
import com.rechang.api.entity.Performance;
import com.rechang.api.entity.PerformancePriceZone;
import com.rechang.api.entity.PerformanceReview;
import com.rechang.api.entity.Seat;
import com.rechang.api.entity.Ticket;
import com.rechang.api.entity.Venue;
import com.rechang.api.mapper.AttendeeMapper;
import com.rechang.api.mapper.OrderMapper;
import com.rechang.api.mapper.PerformanceMapper;
import com.rechang.api.mapper.PerformancePriceZoneMapper;
import com.rechang.api.mapper.PerformanceReviewMapper;
import com.rechang.api.mapper.SeatMapper;
import com.rechang.api.mapper.TicketMapper;
import com.rechang.api.mapper.VenueMapper;
import com.rechang.api.vo.OrderDetailVO;
import com.rechang.api.vo.OrderVO;
import com.rechang.api.vo.PayParamsVO;
import com.rechang.common.exception.BusinessException;
import com.rechang.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderService {

    private static final long LOCK_TTL_MINUTES = 15;
    private static final List<String> SOLD_TICKET_STATUSES = List.of("USABLE", "USED", "TRANSFERRED");
    private static final List<String> ACTIVE_TICKET_STATUSES = List.of("USABLE", "USED", "TRANSFERRED", "PENDING");
    private static final Set<String> PAID_ORDER_STATUSES = Set.of("ISSUED", "ATTENDED", "REVIEWED");

    private final OrderMapper orderMapper;
    private final TicketMapper ticketMapper;
    private final PerformanceMapper performanceMapper;
    private final VenueMapper venueMapper;
    private final SeatMapper seatMapper;
    private final PerformancePriceZoneMapper priceZoneMapper;
    private final AttendeeMapper attendeeMapper;
    private final PerformanceReviewMapper performanceReviewMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    @Transactional
    public OrderVO createOrder(CreateOrderDTO dto, Long userId) {
        Performance perf = performanceMapper.selectById(dto.getPerformanceId());
        if (perf == null || !"ON_SALE".equals(perf.getPublishStatus())) {
            throw new BusinessException(ResultCode.PERFORMANCE_NOT_FOUND);
        }

        List<PerformancePriceZone> zones = priceZoneMapper.selectList(
                new LambdaQueryWrapper<PerformancePriceZone>()
                        .eq(PerformancePriceZone::getPerformanceId, dto.getPerformanceId()));
        Map<String, Integer> priceByRegion = zones.stream()
                .collect(Collectors.toMap(PerformancePriceZone::getRegion, PerformancePriceZone::getPrice, (a, b) -> a));

        boolean isStanding = dto.getSeatIds() == null || dto.getSeatIds().isEmpty();

        List<Ticket> ticketsToCreate = new ArrayList<>();
        int totalAmount = 0;

        if (isStanding) {
            int count = dto.getStandingCount() != null ? dto.getStandingCount() : 0;
            if (count <= 0) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "站票数量必须大于0");
            }
            Integer standingPrice = perf.getMinPrice();
            if (standingPrice == null) {
                standingPrice = zones.stream()
                        .map(PerformancePriceZone::getPrice)
                        .min(Integer::compareTo)
                        .orElse(0);
            }
            for (int i = 0; i < count; i++) {
                Ticket t = new Ticket();
                t.setPerformanceId(dto.getPerformanceId());
                t.setSeatId(null);
                t.setFaceAmount(standingPrice);
                t.setOwnerUserId(userId);
                t.setOriginalUserId(userId);
                t.setStatus("PENDING");
                t.setTransferCount(0);
                t.setFaceVerified(0);
                totalAmount += standingPrice;
                ticketsToCreate.add(t);
            }
        } else {
            List<Long> seatIds = dto.getSeatIds();
            for (Long seatId : seatIds) {
                String lockKey = buildLockKey(dto.getPerformanceId(), seatId);
                if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey))) {
                    throw new BusinessException(ResultCode.SEAT_LOCKED);
                }
            }

            Set<Long> soldSeatIds = getSoldSeatIds(dto.getPerformanceId());
            for (Long seatId : seatIds) {
                if (soldSeatIds.contains(seatId)) {
                    throw new BusinessException(ResultCode.SEAT_SOLD);
                }
            }

            List<Seat> seats = seatMapper.selectBatchIds(seatIds);
            Map<Long, Seat> seatMap = seats.stream().collect(Collectors.toMap(Seat::getId, s -> s));

            for (Long seatId : seatIds) {
                Seat seat = seatMap.get(seatId);
                if (seat == null) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "座位不存在: " + seatId);
                }
                if ("DISABLED".equals(seat.getStatus())) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "座位不可用: " + seatId);
                }
                Integer price = priceByRegion.get(seat.getRegion());
                if (price == null) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "区域未定价: " + seat.getRegion());
                }
                Ticket t = new Ticket();
                t.setPerformanceId(dto.getPerformanceId());
                t.setSeatId(seatId);
                t.setFaceAmount(price);
                t.setOwnerUserId(userId);
                t.setOriginalUserId(userId);
                t.setStatus("PENDING");
                t.setTransferCount(0);
                t.setFaceVerified(0);
                totalAmount += price;
                ticketsToCreate.add(t);
            }
        }

        Integer limit = perf.getPurchaseLimitPerId();
        if (limit == null) {
            limit = 4;
        }
        Long existingCount = ticketMapper.selectCount(
                new LambdaQueryWrapper<Ticket>()
                        .eq(Ticket::getOwnerUserId, userId)
                        .eq(Ticket::getPerformanceId, dto.getPerformanceId())
                        .in(Ticket::getStatus, ACTIVE_TICKET_STATUSES));
        if (existingCount + ticketsToCreate.size() > limit) {
            throw new BusinessException(ResultCode.PURCHASE_LIMIT_EXCEEDED);
        }

        if (dto.getAttendees() != null && !dto.getAttendees().isEmpty()) {
            List<Long> attendeeIds = dto.getAttendees().stream()
                    .map(CreateOrderDTO.AttendeeItem::getAttendeeId)
                    .filter(Objects::nonNull)
                    .toList();
            if (!attendeeIds.isEmpty()) {
                Map<Long, Attendee> attendeeMap = attendeeMapper.selectList(
                                new LambdaQueryWrapper<Attendee>()
                                        .eq(Attendee::getUserId, userId)
                                        .in(Attendee::getId, attendeeIds))
                        .stream().collect(Collectors.toMap(Attendee::getId, a -> a, (a1, a2) -> a1));
                for (int i = 0; i < ticketsToCreate.size() && i < dto.getAttendees().size(); i++) {
                    Long attendeeId = dto.getAttendees().get(i).getAttendeeId();
                    if (attendeeId == null) {
                        throw new BusinessException(ResultCode.BAD_REQUEST, "观演人ID不能为空");
                    }
                    Attendee attendee = attendeeMap.get(attendeeId);
                    if (attendee == null) {
                        throw new BusinessException(ResultCode.BAD_REQUEST, "观演人不存在: " + attendeeId);
                    }
                    ticketsToCreate.get(i).setAttendeeIdCardHash(attendee.getIdCardHash());
                }
            } else {
                for (Ticket t : ticketsToCreate) {
                    t.setAttendeeIdCardHash("");
                }
            }
        } else {
            for (Ticket t : ticketsToCreate) {
                t.setAttendeeIdCardHash("");
            }
        }

        OrderEntity order = new OrderEntity();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId);
        order.setPerformanceId(dto.getPerformanceId());
        order.setTotalAmount(totalAmount);
        order.setRefundedAmount(0);
        order.setSource("PURCHASE");
        order.setStatus("PENDING_PAY");
        order.setVersion(0);
        order.setCreateTime(new Date());
        order.setUpdateTime(new Date());
        orderMapper.insert(order);

        for (Ticket t : ticketsToCreate) {
            t.setOrderId(order.getId());
            t.setCreateTime(new Date());
            ticketMapper.insert(t);
        }

        if (!isStanding) {
            for (Long seatId : dto.getSeatIds()) {
                String lockKey = buildLockKey(dto.getPerformanceId(), seatId);
                redisTemplate.opsForValue().set(lockKey, "LOCKED", LOCK_TTL_MINUTES, TimeUnit.MINUTES);
            }
        }

        return toOrderVO(order, perf, null);
    }

    public List<OrderVO> getOrderList(Long userId, String status) {
        LambdaQueryWrapper<OrderEntity> wrapper = new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getUserId, userId)
                .orderByDesc(OrderEntity::getCreateTime);
        if (status != null && !status.isBlank()) {
            wrapper.eq(OrderEntity::getStatus, status);
        }
        List<OrderEntity> orders = orderMapper.selectList(wrapper);
        if (orders.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> performanceIds = orders.stream().map(OrderEntity::getPerformanceId).collect(Collectors.toSet());
        Map<Long, Performance> perfMap = performanceMapper.selectBatchIds(performanceIds)
                .stream().collect(Collectors.toMap(Performance::getId, p -> p));
        Set<Long> venueIds = perfMap.values().stream()
                .map(Performance::getVenueId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Venue> venueMap = venueIds.isEmpty() ? Collections.emptyMap()
                : venueMapper.selectBatchIds(venueIds).stream().collect(Collectors.toMap(Venue::getId, v -> v));

        return orders.stream()
                .map(o -> toOrderVO(o,
                        perfMap.get(o.getPerformanceId()),
                        o.getPerformanceId() != null && perfMap.containsKey(o.getPerformanceId())
                                ? venueMap.get(perfMap.get(o.getPerformanceId()).getVenueId())
                                : null))
                .toList();
    }

    public OrderDetailVO getOrderDetail(Long orderId, Long userId) {
        OrderEntity order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }

        Performance perf = performanceMapper.selectById(order.getPerformanceId());
        Venue venue = null;
        if (perf != null && perf.getVenueId() != null) {
            venue = venueMapper.selectById(perf.getVenueId());
        }

        OrderDetailVO vo = toOrderDetailVO(order, perf, venue);

        List<Ticket> tickets = ticketMapper.selectList(
                new LambdaQueryWrapper<Ticket>().eq(Ticket::getOrderId, orderId));

        Set<String> cardHashes = tickets.stream()
                .map(Ticket::getAttendeeIdCardHash).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, Attendee> attendeeMap = cardHashes.isEmpty() ? Collections.emptyMap()
                : attendeeMapper.selectList(
                        new LambdaQueryWrapper<Attendee>()
                                .eq(Attendee::getUserId, userId)
                                .in(Attendee::getIdCardHash, cardHashes))
                        .stream().collect(Collectors.toMap(Attendee::getIdCardHash, a -> a, (a1, a2) -> a1));

        Set<Long> seatIds = tickets.stream().map(Ticket::getSeatId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Seat> seatMap = seatIds.isEmpty() ? Collections.emptyMap()
                : seatMapper.selectBatchIds(seatIds).stream().collect(Collectors.toMap(Seat::getId, s -> s));

        List<OrderDetailVO.TicketSimpleVO> ticketVOs = tickets.stream().map(t -> {
            OrderDetailVO.TicketSimpleVO tvo = new OrderDetailVO.TicketSimpleVO();
            tvo.setId(t.getId());
            tvo.setFaceAmount(t.getFaceAmount());
            tvo.setStatus(t.getStatus());
            tvo.setSeatLabel(resolveSeatLabel(t.getSeatId(), seatMap));
            Attendee att = attendeeMap.get(t.getAttendeeIdCardHash());
            tvo.setAttendeeName(att != null ? att.getAttendeeName() : null);
            return tvo;
        }).toList();
        vo.setTickets(ticketVOs);

        List<OrderDetailVO.TimelineItem> timeline = new ArrayList<>();
        addTimelineItem(timeline, "CREATED", order.getCreateTime(), "创建订单");
        if (order.getPaidAt() != null) {
            addTimelineItem(timeline, "PAID", order.getPaidAt(), "支付完成");
        }
        if (order.getCompletedAt() != null) {
            addTimelineItem(timeline, "COMPLETED", order.getCompletedAt(), "出票完成");
        }
        if (order.getCancelledAt() != null) {
            addTimelineItem(timeline, "CANCELLED", order.getCancelledAt(), "订单取消");
        }
        if (order.getRefundedAt() != null) {
            addTimelineItem(timeline, "REFUNDED", order.getRefundedAt(), "订单退款");
        }
        if (order.getReviewedAt() != null) {
            addTimelineItem(timeline, "REVIEWED", order.getReviewedAt(), "已评价");
        }
        vo.setTimeline(timeline);

        if ("REVIEWED".equals(order.getStatus())) {
            PerformanceReview review = performanceReviewMapper.selectOne(
                    new LambdaQueryWrapper<PerformanceReview>()
                            .eq(PerformanceReview::getOrderId, orderId)
                            .last("LIMIT 1"));
            if (review != null) {
                vo.setReviewId(review.getId());
            }
        }

        return vo;
    }

    @Transactional
    public PayParamsVO pay(Long orderId, Long userId) {
        OrderEntity order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (!"PENDING_PAY".equals(order.getStatus())) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "仅待支付订单可发起支付");
        }

        order.setStatus("ISSUED");
        order.setPaidAt(new Date());
        order.setPayChannel("WECHAT");
        order.setUpdateTime(new Date());
        if (orderMapper.updateById(order) == 0) {
            // 乐观锁冲突（如并发取消/重复支付）：以最新状态为准
            OrderEntity latest = orderMapper.selectById(orderId);
            if (latest != null && "ISSUED".equals(latest.getStatus())) {
                return buildMockPayParams(); // 已被并发请求支付成功，幂等返回
            }
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "订单状态已变化，无法完成支付");
        }

        List<Ticket> tickets = ticketMapper.selectList(
                new LambdaQueryWrapper<Ticket>().eq(Ticket::getOrderId, orderId));

        Ticket updateTicket = new Ticket();
        updateTicket.setStatus("USABLE");
        ticketMapper.update(updateTicket, new LambdaQueryWrapper<Ticket>().eq(Ticket::getOrderId, orderId));

        for (Ticket t : tickets) {
            if (t.getSeatId() != null) {
                redisTemplate.delete(buildLockKey(order.getPerformanceId(), t.getSeatId()));
            }
        }

        return buildMockPayParams();
    }

    @Transactional
    public void cancelOrder(Long orderId, Long userId) {
        OrderEntity order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (!"PENDING_PAY".equals(order.getStatus())) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "仅待支付订单可取消");
        }

        order.setStatus("CANCELLED");
        order.setCancelledAt(new Date());
        order.setCancelReason("USER");
        order.setUpdateTime(new Date());
        if (orderMapper.updateById(order) == 0) {
            // 乐观锁冲突：并发支付已赢（或状态已变），绝不能继续删除已支付订单的票
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "订单状态已变化，无法取消");
        }

        List<Ticket> tickets = ticketMapper.selectList(
                new LambdaQueryWrapper<Ticket>().eq(Ticket::getOrderId, orderId));
        for (Ticket t : tickets) {
            if (t.getSeatId() != null) {
                redisTemplate.delete(buildLockKey(order.getPerformanceId(), t.getSeatId()));
            }
        }
        ticketMapper.delete(new LambdaQueryWrapper<Ticket>().eq(Ticket::getOrderId, orderId));
    }

    public Map<String, Object> getPayStatus(Long orderId, Long userId) {
        OrderEntity order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("status", order.getStatus());
        result.put("paid", PAID_ORDER_STATUSES.contains(order.getStatus()));
        return result;
    }

    private OrderVO toOrderVO(OrderEntity order, Performance perf, Venue venue) {
        OrderVO vo = new OrderVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setUserId(order.getUserId());
        vo.setPerformanceId(order.getPerformanceId());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setRefundedAmount(order.getRefundedAmount());
        vo.setPayChannel(order.getPayChannel());
        vo.setSource(order.getSource());
        vo.setStatus(order.getStatus());
        vo.setPaidAt(order.getPaidAt());
        vo.setCompletedAt(order.getCompletedAt());
        vo.setCreateTime(order.getCreateTime());
        if (perf != null) {
            vo.setPerformanceName(perf.getPerfName());
            vo.setPosterUrl(perf.getPosterUrl());
            vo.setStartAt(perf.getStartAt());
            if (venue != null) {
                vo.setVenueName(venue.getVenueName());
            }
        }
        return vo;
    }

    private OrderDetailVO toOrderDetailVO(OrderEntity order, Performance perf, Venue venue) {
        OrderDetailVO vo = new OrderDetailVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setUserId(order.getUserId());
        vo.setPerformanceId(order.getPerformanceId());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setRefundedAmount(order.getRefundedAmount());
        vo.setPayChannel(order.getPayChannel());
        vo.setSource(order.getSource());
        vo.setStatus(order.getStatus());
        vo.setPaidAt(order.getPaidAt());
        vo.setCompletedAt(order.getCompletedAt());
        vo.setReviewedAt(order.getReviewedAt());
        vo.setCreateTime(order.getCreateTime());
        if (perf != null) {
            vo.setPerformanceName(perf.getPerfName());
            vo.setPosterUrl(perf.getPosterUrl());
            vo.setStartAt(perf.getStartAt());
            if (venue != null) {
                vo.setVenueName(venue.getVenueName());
            }
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

    private void addTimelineItem(List<OrderDetailVO.TimelineItem> timeline, String status, Date time, String label) {
        OrderDetailVO.TimelineItem item = new OrderDetailVO.TimelineItem();
        item.setStatus(status);
        item.setTime(time);
        item.setLabel(label);
        timeline.add(item);
    }

    private Set<Long> getSoldSeatIds(Long performanceId) {
        List<Ticket> tickets = ticketMapper.selectList(
                new LambdaQueryWrapper<Ticket>()
                        .eq(Ticket::getPerformanceId, performanceId)
                        .isNotNull(Ticket::getSeatId)
                        .in(Ticket::getStatus, SOLD_TICKET_STATUSES));
        return tickets.stream().map(Ticket::getSeatId).collect(Collectors.toSet());
    }

    private String buildLockKey(Long performanceId, Long seatId) {
        return "seat:lock:" + performanceId + ":" + seatId;
    }

    private String generateOrderNo() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        int random = new Random().nextInt(9000) + 1000;
        return "RC" + sdf.format(new Date()) + random;
    }

    private PayParamsVO buildMockPayParams() {
        PayParamsVO vo = new PayParamsVO();
        vo.setTimeStamp(String.valueOf(System.currentTimeMillis() / 1000));
        vo.setNonceStr(UUID.randomUUID().toString().replace("-", ""));
        vo.setPackageStr("prepay_id=wx" + System.currentTimeMillis());
        vo.setSignType("RSA");
        vo.setPaySign("MOCK_SIGNATURE_" + System.currentTimeMillis());
        return vo;
    }
}
