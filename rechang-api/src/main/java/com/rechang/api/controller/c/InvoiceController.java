package com.rechang.api.controller.c;

import com.rechang.api.dto.InvoiceDTO;
import com.rechang.api.security.UserContext;
import com.rechang.api.service.InvoiceService;
import com.rechang.api.vo.InvoiceVO;
import com.rechang.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;

    @GetMapping("/invoices")
    public Result<List<InvoiceVO>> list(
            @RequestParam(required = false) String status) {
        return Result.success(invoiceService.getInvoiceList(UserContext.getUserId(), status));
    }

    @GetMapping("/orders/{id}/invoice")
    public Result<InvoiceVO> getInvoice(@PathVariable Long id) {
        return Result.success(invoiceService.getOrderInvoice(id, UserContext.getUserId()));
    }

    @PostMapping("/orders/{id}/invoice")
    public Result<InvoiceVO> apply(@PathVariable Long id, @Valid @RequestBody InvoiceDTO dto) {
        return Result.success(invoiceService.applyInvoice(id, dto, UserContext.getUserId()));
    }

    @GetMapping("/invoices/{id}/download")
    public Result<String> download(@PathVariable Long id) {
        return Result.success(invoiceService.downloadInvoice(id, UserContext.getUserId()));
    }
}
