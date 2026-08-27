package com.rechang.api.controller.c;

import com.rechang.api.entity.OrderEntity;
import com.rechang.api.security.UserContext;
import com.rechang.api.service.TicketService;
import com.rechang.api.service.TransferService;
import com.rechang.api.vo.TicketVO;
import com.rechang.api.vo.TransferPreviewVO;
import com.rechang.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;
    private final TransferService transferService;

    @GetMapping
    public Result<List<TicketVO>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(ticketService.getTicketList(UserContext.getUserId(), status));
    }

    @GetMapping("/{id}/qrcode")
    public Result<Map<String, Object>> qrcode(@PathVariable Long id) {
        return Result.success(ticketService.getQrCode(id, UserContext.getUserId()));
    }

    @PostMapping("/{id}/transfer")
    public Result<TransferPreviewVO> startTransfer(@PathVariable Long id) {
        return Result.success(transferService.startTransfer(id, UserContext.getUserId()));
    }

    @GetMapping("/transfer/preview")
    public Result<TransferPreviewVO> previewTransfer(@RequestParam String transferToken) {
        return Result.success(transferService.previewTransfer(transferToken, UserContext.getUserId()));
    }

    @PostMapping("/transfer/claim")
    public Result<OrderEntity> claimTransfer(@RequestBody Map<String, String> body) {
        String token = body.get("transferToken");
        if (token == null) {
            token = body.get("transfer_token");
        }
        return Result.success(transferService.claimTransfer(token, UserContext.getUserId()));
    }

    @PostMapping("/{id}/verify")
    public Result<Map<String, Object>> verify(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String qrContent = body.get("qrContent");
        String signature = body.get("signature");
        String faceVerifyResult = body.get("faceVerifyResult");
        return Result.success(ticketService.verifyTicket(id, qrContent, signature, faceVerifyResult));
    }
}

