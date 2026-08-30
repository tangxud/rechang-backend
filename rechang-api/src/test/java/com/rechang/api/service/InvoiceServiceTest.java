package com.rechang.api.service;

import com.rechang.api.dto.InvoiceDTO;
import com.rechang.api.entity.Invoice;
import com.rechang.api.mapper.InvoiceMapper;
import com.rechang.api.mapper.OrderMapper;
import com.rechang.api.mapper.PerformanceMapper;
import com.rechang.api.support.Fixtures;
import com.rechang.api.vo.InvoiceVO;
import com.rechang.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开票资格守卫与金额计算（已知缺陷：VOIDED 状态机未实现，见测试计划缺陷登记）。
 */
@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    @Mock InvoiceMapper invoiceMapper;
    @Mock OrderMapper orderMapper;
    @Mock PerformanceMapper performanceMapper;
    @InjectMocks InvoiceService invoiceService;

    private InvoiceDTO dto(String titleType, String taxNo) {
        InvoiceDTO dto = new InvoiceDTO();
        dto.setTitleType(titleType);
        dto.setInvoiceTitle("热场科技有限公司");
        dto.setTaxNo(taxNo);
        dto.setEmail("invoice@example.com");
        return dto;
    }

    @Test
    @DisplayName("订单不存在或不属于本人 → ORDER_NOT_FOUND")
    void orderGuard() {
        when(orderMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> invoiceService.applyInvoice(Fixtures.ORDER_ID, dto("PERSONAL", null), Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 1009);
    }

    @Test
    @DisplayName("订单状态不在可开票白名单 → ORDER_NOT_INVOICEABLE")
    void statusWhitelist() {
        when(orderMapper.selectOne(any())).thenReturn(
                Fixtures.order(Fixtures.ORDER_ID, Fixtures.USER_A, Fixtures.PERF_ID, "PENDING_PAY"));
        assertThatThrownBy(() -> invoiceService.applyInvoice(Fixtures.ORDER_ID, dto("PERSONAL", null), Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 1010);
    }

    @Test
    @DisplayName("已有非 VOIDED 发票 → INVOICE_DUPLICATE")
    void duplicateInvoice() {
        when(orderMapper.selectOne(any())).thenReturn(
                Fixtures.order(Fixtures.ORDER_ID, Fixtures.USER_A, Fixtures.PERF_ID, "ISSUED"));
        when(invoiceMapper.selectOne(any())).thenReturn(new Invoice());
        assertThatThrownBy(() -> invoiceService.applyInvoice(Fixtures.ORDER_ID, dto("PERSONAL", null), Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 1011);
    }

    @Test
    @DisplayName("企业抬头必须提供税号")
    void enterpriseRequiresTaxNo() {
        when(orderMapper.selectOne(any())).thenReturn(
                Fixtures.order(Fixtures.ORDER_ID, Fixtures.USER_A, Fixtures.PERF_ID, "ISSUED"));
        when(invoiceMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> invoiceService.applyInvoice(Fixtures.ORDER_ID, dto("ENTERPRISE", " "), Fixtures.USER_A))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("税号");
    }

    @Test
    @DisplayName("开票成功：金额=total-refunded，即开即 ISSUED，单号 INV 前缀")
    void applySuccess() {
        var order = Fixtures.order(Fixtures.ORDER_ID, Fixtures.USER_A, Fixtures.PERF_ID, "ISSUED");
        order.setTotalAmount(76000);
        order.setRefundedAmount(38000);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(invoiceMapper.selectOne(any())).thenReturn(null);

        InvoiceVO vo = invoiceService.applyInvoice(Fixtures.ORDER_ID, dto("PERSONAL", null), Fixtures.USER_A);

        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceMapper).insert(captor.capture());
        Invoice inv = captor.getValue();
        assertThat(inv.getAmount()).isEqualTo(38000);
        assertThat(inv.getStatus()).isEqualTo("ISSUED");
        assertThat(inv.getInvoiceNo()).startsWith("INV");
        assertThat(inv.getInvoiceUrl()).isEqualTo("/invoices/" + inv.getInvoiceNo() + ".pdf");
        assertThat(vo.getAmount()).isEqualTo(38000);
    }

    @Test
    @DisplayName("下载：非本人 NOT_FOUND；VOIDED/BAD 状态拒绝；ISSUED 返回 url")
    void downloadFlows() {
        Invoice voided = new Invoice();
        voided.setId(1L);
        voided.setUserId(Fixtures.USER_A);
        voided.setStatus("VOIDED");
        voided.setInvoiceUrl("/invoices/x.pdf");
        when(invoiceMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> invoiceService.downloadInvoice(1L, Fixtures.USER_A))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("发票不存在");

        when(invoiceMapper.selectOne(any())).thenReturn(voided);
        assertThatThrownBy(() -> invoiceService.downloadInvoice(1L, Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 400)
                .hasMessageContaining("未开具");

        voided.setStatus("ISSUED");
        assertThat(invoiceService.downloadInvoice(1L, Fixtures.USER_A)).isEqualTo("/invoices/x.pdf");
    }

    @Test
    @DisplayName("订单未开过票 → getOrderInvoice 返回 null")
    void orderInvoiceEmpty() {
        when(invoiceMapper.selectOne(any())).thenReturn(null);
        assertThat(invoiceService.getOrderInvoice(Fixtures.ORDER_ID, Fixtures.USER_A)).isNull();
    }

    /* ================= voidInvoice（全额退款联动，票 #30003） ================= */

    @Test
    @DisplayName("voidInvoice：ISSUED 发票置 VOIDED 并更新")
    void voidInvoiceMarksVoided() {
        Invoice inv = new Invoice();
        inv.setId(5L);
        inv.setOrderId(Fixtures.ORDER_ID);
        inv.setStatus("ISSUED");
        when(invoiceMapper.selectOne(any())).thenReturn(inv);

        invoiceService.voidInvoice(Fixtures.ORDER_ID);

        ArgumentCaptor<Invoice> cap = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceMapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("VOIDED");
        assertThat(cap.getValue().getUpdateTime()).isNotNull();
    }

    @Test
    @DisplayName("voidInvoice：无有效发票（未开过/已作废）时幂等静默，不触发更新")
    void voidInvoiceIdempotent() {
        when(invoiceMapper.selectOne(any())).thenReturn(null);

        invoiceService.voidInvoice(Fixtures.ORDER_ID);

        verify(invoiceMapper, never()).updateById(any(Invoice.class));
    }

    /* ================= 作废后重开（uk_order 降级普通索引，防重走应用层） ================= */

    @Test
    @DisplayName("作废后重开：仅剩 VOIDED 历史（防重查询排除 VOIDED → 返回空）可再次开票成功")
    void reopenAfterVoid() {
        var order = Fixtures.order(Fixtures.ORDER_ID, Fixtures.USER_A, Fixtures.PERF_ID, "ISSUED");
        order.setTotalAmount(38000);
        when(orderMapper.selectOne(any())).thenReturn(order);
        // 真实 DB 语义：该订单只剩 VOIDED 行时，ne(VOIDED) 查询返回空
        when(invoiceMapper.selectOne(any())).thenReturn(null);

        InvoiceVO vo = invoiceService.applyInvoice(Fixtures.ORDER_ID, dto("PERSONAL", null), Fixtures.USER_A);

        assertThat(vo.getStatus()).isEqualTo("ISSUED");
        ArgumentCaptor<Invoice> cap = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceMapper).insert(cap.capture());
        assertThat(cap.getValue().getOrderId()).isEqualTo(Fixtures.ORDER_ID);
        assertThat(cap.getValue().getAmount()).isEqualTo(38000);
    }
}
