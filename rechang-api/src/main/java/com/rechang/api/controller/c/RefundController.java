package com.rechang.api.controller.c;

import com.rechang.api.dto.RefundDTO;
import com.rechang.api.security.UserContext;
import com.rechang.api.service.RefundService;
import com.rechang.api.vo.RefundPreviewVO;
import com.rechang.api.vo.RefundRecordVO;
import com.rechang.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class RefundController {

    private final RefundService refundService;

    @GetMapping("/{id}/tickets/{ticketId}/refund/preview")
    public Result<RefundPreviewVO> preview(@PathVariable Long id, @PathVariable Long ticketId) {
        return Result.success(refundService.previewRefund(id, ticketId, UserContext.getUserId()));
    }

    @PostMapping("/{id}/tickets/{ticketId}/refund")
    public Result<RefundRecordVO> refund(@PathVariable Long id, @PathVariable Long ticketId, @RequestBody RefundDTO dto) {
        return Result.success(refundService.refundTicket(id, ticketId, dto, UserContext.getUserId()));
    }

    @PostMapping("/{id}/tickets/{ticketId}/refund/force-majeure")
    public Result<RefundRecordVO> forceMajeure(@PathVariable Long id, @PathVariable Long ticketId, @RequestBody RefundDTO dto) {
        return Result.success(refundService.refundForceMajeure(id, ticketId, dto, UserContext.getUserId()));
    }

    @GetMapping("/{id}/refund/record")
    public Result<List<RefundRecordVO>> records(@PathVariable Long id) {
        return Result.success(refundService.getRefundRecords(id, UserContext.getUserId()));
    }
}
