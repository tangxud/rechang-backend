package com.rechang.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.rechang.api.vo.RefundPreviewVO;
import com.rechang.api.vo.RefundRecordVO;
import com.rechang.common.exception.BusinessException;
import com.rechang.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RefundService {

    private static final Set<String> REFUNDABLE_ORDER_STATUS = Set.of("ISSUED", "ATTENDED");
    private static final List<String> ACTIVE_TICKET_STATUSES = List.of("USABLE", "USED", "TRANSFERRED");
    private static final long MS_PER_DAY = 24L * 60 * 60 * 1000;
    private static final long MS_48H = 48L * 60 * 60 * 1000;
    private static final long MS_24H = 24L * 60 * 60 * 1000;

    private final RefundRecordMapper refundRecordMapper;
    private final OrderMapper orderMapper;
    private final TicketMapper ticketMapper;
    private final PerformanceMapper performanceMapper;
    private final SeatMapper seatMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final InvoiceService invoiceService;

    public RefundPreviewVO previewRefund(Long orderId, Long ticketId, Long userId) {
        OrderEntity order = getOrder(orderId, userId);
        Ticket ticket = getTicket(orderId, ticketId);
        Performance perf = getPerformance(order.getPerformanceId());

        OrderEntity payOrder = resolvePayOrder(order);
        RefundStage stage = calculateStage(payOrder.getPaidAt(), perf.getStartAt());
        int feeAmount = ticket.getFaceAmount() * stage.feeRate() / 1000;
        int refundAmount = ticket.getFaceAmount() - feeAmount;

        RefundPreviewVO vo = new RefundPreviewVO();
        vo.setTicketId(ticketId);
        vo.setSeatLabel(resolveSeatLabel(ticket.getSeatId()));
        vo.setTicketAmount(ticket.getFaceAmount());
        vo.setStage(stage.stage());
        vo.setFeeRate(stage.feeRate());
        vo.setFeeAmount(feeAmount);
        vo.setRefundAmount(refundAmount);
        vo.setRefundable(stage.refundable());
        vo.setForceMajeureAvailable(true);
        vo.setEstimatedArrival(stage.refundable() ? "预计1-3个工作日到账" : "不可退票");
        vo.setStageDesc(stageDesc(stage.stage()));
        return vo;
    }

    @Transactional
    public RefundRecordVO refundTicket(Long orderId, Long ticketId, RefundDTO dto, Long userId) {
        OrderEntity order = getOrder(orderId, userId);
        Ticket ticket = getTicket(orderId, ticketId);
        Performance perf = getPerformance(order.getPerformanceId());

        OrderEntity payOrder = resolvePayOrder(order);
        RefundStage stage = calculateStage(payOrder.getPaidAt(), perf.getStartAt());
        if (!stage.refundable()) {
            throw new BusinessException(ResultCode.TICKET_NOT_REFUNDABLE);
        }

        int feeAmount = ticket.getFaceAmount() * stage.feeRate() / 1000;
        int refundAmount = ticket.getFaceAmount() - feeAmount;
        Date now = new Date();

        RefundRecord record = new RefundRecord();
        record.setRefundNo(generateRefundNo());
        record.setOrderId(orderId);
        record.setTicketId(ticketId);
        record.setUserId(userId);
        record.setRefundType("PERSONAL");
        record.setTicketAmount(ticket.getFaceAmount());
        record.setFeeRate(stage.feeRate());
        record.setFeeAmount(feeAmount);
        record.setRefundAmount(refundAmount);
        record.setPayChannel(payOrder.getPayChannel());
        record.setStatus("SUCCESS");
        record.setChannelRefundNo("MOCK_RF_" + System.currentTimeMillis());
        record.setEvidenceUrls("");
        record.setReviewedBy(0L);
        record.setReviewRemark("");
        record.setRefundedAt(now);
        record.setCreateTime(now);
        record.setUpdateTime(now);
        refundRecordMapper.insert(record);

        ticket.setStatus("REFUNDED");
        ticketMapper.updateById(ticket);

        int newRefunded = (order.getRefundedAmount() != null ? order.getRefundedAmount() : 0) + refundAmount;
        order.setRefundedAmount(newRefunded);
        updateOrderStatusIfAllRefunded(order, orderId, now);
        order.setUpdateTime(now);
        if (orderMapper.updateById(order) == 0) {
            // RR 快照下事务内重试无效，冲突即回滚由用户重试（并发退同一订单的另一张票）
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "订单状态已变化，请重试退款");
        }

        if (ticket.getSeatId() != null) {
            redisTemplate.delete("seat:lock:" + order.getPerformanceId() + ":" + ticket.getSeatId());
        }

        return toRefundRecordVO(record, resolveSeatLabel(ticket.getSeatId()), perf.getPerfName());
    }

    @Transactional
    public RefundRecordVO refundForceMajeure(Long orderId, Long ticketId, RefundDTO dto, Long userId) {
        OrderEntity order = getOrder(orderId, userId);
        Ticket ticket = getTicket(orderId, ticketId);
        Performance perf = getPerformance(order.getPerformanceId());

        if (dto.getEvidenceUrls() == null || dto.getEvidenceUrls().isEmpty()) {
            throw new BusinessException(ResultCode.EVIDENCE_REQUIRED);
        }

        OrderEntity payOrder = resolvePayOrder(order);
        Date now = new Date();
        int refundAmount = ticket.getFaceAmount();

        RefundRecord record = new RefundRecord();
        record.setRefundNo(generateRefundNo());
        record.setOrderId(orderId);
        record.setTicketId(ticketId);
        record.setUserId(userId);
        record.setRefundType("FORCE_MAJEURE");
        record.setTicketAmount(ticket.getFaceAmount());
        record.setFeeRate(0);
        record.setFeeAmount(0);
        record.setRefundAmount(refundAmount);
        record.setPayChannel(payOrder.getPayChannel());
        record.setStatus("PENDING");
        record.setChannelRefundNo("");
        record.setEvidenceUrls(serializeEvidenceUrls(dto.getEvidenceUrls()));
        record.setReviewedBy(0L);
        record.setReviewRemark("");
        record.setCreateTime(now);
        record.setUpdateTime(now);
        refundRecordMapper.insert(record);

        ticket.setStatus("REFUNDED");
        ticketMapper.updateById(ticket);

        int newRefunded = (order.getRefundedAmount() != null ? order.getRefundedAmount() : 0) + refundAmount;
        order.setRefundedAmount(newRefunded);
        updateOrderStatusIfAllRefunded(order, orderId, now);
        order.setUpdateTime(now);
        if (orderMapper.updateById(order) == 0) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "订单状态已变化，请重试退款");
        }

        return toRefundRecordVO(record, resolveSeatLabel(ticket.getSeatId()), perf.getPerfName());
    }

    public List<RefundRecordVO> getRefundRecords(Long orderId, Long userId) {
        OrderEntity order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        Performance perf = performanceMapper.selectById(order.getPerformanceId());

        List<RefundRecord> records = refundRecordMapper.selectList(
                new LambdaQueryWrapper<RefundRecord>()
                        .eq(RefundRecord::getOrderId, orderId)
                        .eq(RefundRecord::getUserId, userId)
                        .orderByDesc(RefundRecord::getCreateTime));
        if (records.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> ticketIds = records.stream().map(RefundRecord::getTicketId).collect(Collectors.toSet());
        Map<Long, Ticket> ticketMap = ticketMapper.selectBatchIds(ticketIds).stream()
                .collect(Collectors.toMap(Ticket::getId, t -> t));

        Set<Long> seatIds = ticketMap.values().stream()
                .map(Ticket::getSeatId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Seat> seatMap = seatIds.isEmpty() ? Collections.emptyMap()
                : seatMapper.selectBatchIds(seatIds).stream().collect(Collectors.toMap(Seat::getId, s -> s));

        String perfName = perf != null ? perf.getPerfName() : null;

        return records.stream().map(r -> {
            Ticket t = ticketMap.get(r.getTicketId());
            String seatLabel = t != null ? resolveSeatLabel(t.getSeatId(), seatMap) : "未知";
            return toRefundRecordVO(r, seatLabel, perfName);
        }).toList();
    }

    private OrderEntity getOrder(Long orderId, Long userId) {
        OrderEntity order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (!REFUNDABLE_ORDER_STATUS.contains(order.getStatus())) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "订单状态不支持退票");
        }
        return order;
    }

    /**
     * 转赠订单退款溯源：转赠订单 total_amount=0、paid_at=NULL，
     * 通过 original_pay_order_id 找到实际支付订单，取其 paid_at 计算手续费阶段、取 pay_channel 作为退款渠道。
     * 直接购买订单 original_pay_order_id=自身，返回自身。
     */
    private OrderEntity resolvePayOrder(OrderEntity order) {
        if (!"TRANSFER".equals(order.getSource())) {
            return order;
        }
        Long payOrderId = order.getOriginalPayOrderId() != null && order.getOriginalPayOrderId() > 0
                ? order.getOriginalPayOrderId()
                : order.getOriginalOrderId();
        if (payOrderId != null && payOrderId > 0) {
            OrderEntity payOrder = orderMapper.selectById(payOrderId);
            if (payOrder != null) {
                return payOrder;
            }
        }
        throw new BusinessException(ResultCode.ORDER_NOT_FOUND, "无法溯源原支付订单");
    }

    private Ticket getTicket(Long orderId, Long ticketId) {
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null || !ticket.getOrderId().equals(orderId)) {
            throw new BusinessException(ResultCode.TICKET_NOT_FOUND);
        }
        if (!"USABLE".equals(ticket.getStatus())) {
            throw new BusinessException(ResultCode.TICKET_NOT_USABLE);
        }
        return ticket;
    }

    private Performance getPerformance(Long performanceId) {
        Performance perf = performanceMapper.selectById(performanceId);
        if (perf == null) {
            throw new BusinessException(ResultCode.PERFORMANCE_NOT_FOUND);
        }
        return perf;
    }

    private void updateOrderStatusIfAllRefunded(OrderEntity order, Long orderId, Date now) {
        Long activeCount = ticketMapper.selectCount(
                new LambdaQueryWrapper<Ticket>()
                        .eq(Ticket::getOrderId, orderId)
                        .in(Ticket::getStatus, ACTIVE_TICKET_STATUSES));
        if (activeCount == 0) {
            order.setStatus("REFUNDED");
            order.setRefundedAt(now);
            // 全额退款联动作废发票（PRD §8.9），幂等；作废后同订单可重开
            invoiceService.voidInvoice(orderId);
        }
    }

    private RefundStage calculateStage(Date paidAt, Date performanceStartAt) {
        Date now = new Date();
        long diffToPerfMs = performanceStartAt.getTime() - now.getTime();
        long daysToPerf = diffToPerfMs / MS_PER_DAY;

        if (diffToPerfMs < MS_24H) {
            return new RefundStage("NOT_REFUNDABLE", 0, false);
        }

        long diffSincePaidMs = now.getTime() - paidAt.getTime();
        boolean inRegretPeriod = diffSincePaidMs < MS_48H;
        if (inRegretPeriod && daysToPerf >= 7) {
            return new RefundStage("REGRET", 0, true);
        }

        if (daysToPerf >= 7) {
            return new RefundStage("EARLY", 0, true);
        }

        if (daysToPerf >= 3) {
            return new RefundStage("MID", 200, true);
        }

        if (daysToPerf >= 1) {
            return new RefundStage("LATE", 500, true);
        }

        return new RefundStage("NOT_REFUNDABLE", 0, false);
    }

    private String stageDesc(String stage) {
        return switch (stage) {
            case "REGRET" -> "购后悔期（0%手续费）";
            case "EARLY" -> "早期退票（0%手续费）";
            case "MID" -> "中期退票（20%手续费）";
            case "LATE" -> "晚期退票（50%手续费）";
            default -> "临近演出不可退票";
        };
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

    private RefundRecordVO toRefundRecordVO(RefundRecord record, String seatLabel, String performanceName) {
        RefundRecordVO vo = new RefundRecordVO();
        vo.setId(record.getId());
        vo.setRefundNo(record.getRefundNo());
        vo.setOrderId(record.getOrderId());
        vo.setTicketId(record.getTicketId());
        vo.setRefundType(record.getRefundType());
        vo.setTicketAmount(record.getTicketAmount());
        vo.setFeeRate(record.getFeeRate());
        vo.setFeeAmount(record.getFeeAmount());
        vo.setRefundAmount(record.getRefundAmount());
        vo.setPayChannel(record.getPayChannel());
        vo.setStatus(record.getStatus());
        vo.setEvidenceUrls(record.getEvidenceUrls());
        vo.setRefundedAt(record.getRefundedAt());
        vo.setCreateTime(record.getCreateTime());
        vo.setSeatLabel(seatLabel);
        vo.setPerformanceName(performanceName);
        return vo;
    }

    private String serializeEvidenceUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(urls);
        } catch (Exception e) {
            return "";
        }
    }

    private String generateRefundNo() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        int random = ThreadLocalRandom.current().nextInt(1000, 10000);
        return "RF" + sdf.format(new Date()) + random;
    }

    private record RefundStage(String stage, int feeRate, boolean refundable) {}
}
