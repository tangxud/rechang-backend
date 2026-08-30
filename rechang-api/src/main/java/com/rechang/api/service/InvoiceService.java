package com.rechang.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rechang.api.entity.Invoice;
import com.rechang.api.entity.OrderEntity;
import com.rechang.api.entity.Performance;
import com.rechang.api.dto.InvoiceDTO;
import com.rechang.api.mapper.InvoiceMapper;
import com.rechang.api.mapper.OrderMapper;
import com.rechang.api.mapper.PerformanceMapper;
import com.rechang.api.vo.InvoiceVO;
import com.rechang.common.exception.BusinessException;
import com.rechang.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InvoiceService {

    private static final Set<String> INVOICEABLE_ORDER_STATUS = Set.of("ISSUED", "ATTENDED", "REVIEWED");

    private final InvoiceMapper invoiceMapper;
    private final OrderMapper orderMapper;
    private final PerformanceMapper performanceMapper;

    public List<InvoiceVO> getInvoiceList(Long userId, String status) {
        LambdaQueryWrapper<Invoice> wrapper = new LambdaQueryWrapper<Invoice>()
                .eq(Invoice::getUserId, userId)
                .orderByDesc(Invoice::getCreateTime);
        if (status != null && !status.isBlank()) {
            wrapper.eq(Invoice::getStatus, status);
        }
        List<Invoice> invoices = invoiceMapper.selectList(wrapper);
        if (invoices.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> orderIds = invoices.stream().map(Invoice::getOrderId).collect(Collectors.toSet());
        Map<Long, OrderEntity> orderMap = orderMapper.selectBatchIds(orderIds).stream()
                .collect(Collectors.toMap(OrderEntity::getId, o -> o));

        Set<Long> performanceIds = orderMap.values().stream()
                .map(OrderEntity::getPerformanceId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Performance> perfMap = performanceIds.isEmpty() ? Collections.emptyMap()
                : performanceMapper.selectBatchIds(performanceIds).stream()
                .collect(Collectors.toMap(Performance::getId, p -> p));

        return invoices.stream().map(inv -> toVO(inv, orderMap, perfMap)).toList();
    }

    public InvoiceVO getOrderInvoice(Long orderId, Long userId) {
        Invoice invoice = invoiceMapper.selectOne(
                new LambdaQueryWrapper<Invoice>()
                        .eq(Invoice::getOrderId, orderId)
                        .eq(Invoice::getUserId, userId)
                        .orderByDesc(Invoice::getCreateTime)
                        .last("LIMIT 1"));
        if (invoice == null) {
            return null;
        }
        return toVO(invoice, Collections.emptyMap(), Collections.emptyMap());
    }

    public InvoiceVO applyInvoice(Long orderId, InvoiceDTO dto, Long userId) {
        OrderEntity order = orderMapper.selectOne(
                new LambdaQueryWrapper<OrderEntity>()
                        .eq(OrderEntity::getId, orderId)
                        .eq(OrderEntity::getUserId, userId));
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (!INVOICEABLE_ORDER_STATUS.contains(order.getStatus())) {
            throw new BusinessException(ResultCode.ORDER_NOT_INVOICEABLE);
        }

        Invoice existing = invoiceMapper.selectOne(
                new LambdaQueryWrapper<Invoice>()
                        .eq(Invoice::getOrderId, orderId)
                        .ne(Invoice::getStatus, "VOIDED")
                        .last("LIMIT 1"));
        if (existing != null) {
            throw new BusinessException(ResultCode.INVOICE_DUPLICATE);
        }

        if ("ENTERPRISE".equals(dto.getTitleType()) && (dto.getTaxNo() == null || dto.getTaxNo().isBlank())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "企业开票需提供税号");
        }

        int amount = order.getTotalAmount() - (order.getRefundedAmount() != null ? order.getRefundedAmount() : 0);

        Invoice invoice = new Invoice();
        invoice.setUserId(userId);
        invoice.setOrderId(orderId);
        invoice.setTitleType(dto.getTitleType());
        invoice.setInvoiceTitle(dto.getInvoiceTitle());
        invoice.setTaxNo(dto.getTaxNo() != null ? dto.getTaxNo() : "");
        invoice.setEmail(dto.getEmail());
        invoice.setAmount(amount);
        invoice.setStatus("ISSUED");
        invoice.setInvoiceNo(generateInvoiceNo());
        invoice.setInvoiceUrl("/invoices/" + invoice.getInvoiceNo() + ".pdf");
        invoice.setIssuedAt(new Date());
        invoiceMapper.insert(invoice);

        return toVO(invoice, Collections.emptyMap(), Collections.emptyMap());
    }

    public String downloadInvoice(Long invoiceId, Long userId) {
        Invoice invoice = invoiceMapper.selectOne(
                new LambdaQueryWrapper<Invoice>()
                        .eq(Invoice::getId, invoiceId)
                        .eq(Invoice::getUserId, userId));
        if (invoice == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "发票不存在");
        }
        if (!"ISSUED".equals(invoice.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "发票未开具");
        }
        return invoice.getInvoiceUrl();
    }

    /**
     * 全额退款联动作废发票（PRD §8.9）：ISSUED → VOIDED。
     * 幂等：无有效发票（未开过/已作废）时静默返回。
     * 作废后同订单可重新开票：invoice.order_id 已降级为普通索引 ix_order，
     * 「一单一有效发票」由 applyInvoice 的应用层防重（查询排除 VOIDED）保证。
     * 由 RefundService 在订单聚合为 REFUNDED 时调用，与退款同事务。
     */
    public void voidInvoice(Long orderId) {
        Invoice invoice = invoiceMapper.selectOne(new LambdaQueryWrapper<Invoice>()
                .eq(Invoice::getOrderId, orderId)
                .eq(Invoice::getStatus, "ISSUED")
                .last("LIMIT 1"));
        if (invoice == null) {
            return;
        }
        invoice.setStatus("VOIDED");
        invoice.setUpdateTime(new Date());
        invoiceMapper.updateById(invoice);
    }

    private InvoiceVO toVO(Invoice invoice, Map<Long, OrderEntity> orderMap, Map<Long, Performance> perfMap) {
        InvoiceVO vo = new InvoiceVO();
        vo.setId(invoice.getId());
        vo.setUserId(invoice.getUserId());
        vo.setOrderId(invoice.getOrderId());
        vo.setTitleType(invoice.getTitleType());
        vo.setInvoiceTitle(invoice.getInvoiceTitle());
        vo.setTaxNo(invoice.getTaxNo());
        vo.setEmail(invoice.getEmail());
        vo.setAmount(invoice.getAmount());
        vo.setStatus(invoice.getStatus());
        vo.setInvoiceNo(invoice.getInvoiceNo());
        vo.setInvoiceUrl(invoice.getInvoiceUrl());
        vo.setIssuedAt(invoice.getIssuedAt());
        vo.setCreateTime(invoice.getCreateTime());

        OrderEntity order = orderMap.get(invoice.getOrderId());
        if (order != null) {
            vo.setOrderNo(order.getOrderNo());
            Performance perf = perfMap.get(order.getPerformanceId());
            if (perf != null) {
                vo.setPerformanceName(perf.getPerfName());
            }
        }

        return vo;
    }

    private String generateInvoiceNo() {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int random = ThreadLocalRandom.current().nextInt(100000, 1000000);
        return "INV" + dateStr + random;
    }
}
