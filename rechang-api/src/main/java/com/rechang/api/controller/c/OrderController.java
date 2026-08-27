package com.rechang.api.controller.c;

import com.rechang.api.dto.CreateOrderDTO;
import com.rechang.api.security.UserContext;
import com.rechang.api.service.OrderService;
import com.rechang.api.vo.OrderDetailVO;
import com.rechang.api.vo.OrderVO;
import com.rechang.api.vo.PayParamsVO;
import com.rechang.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public Result<OrderVO> create(@Valid @RequestBody CreateOrderDTO dto) {
        return Result.success(orderService.createOrder(dto, UserContext.getUserId()));
    }

    @GetMapping
    public Result<List<OrderVO>> list(@RequestParam(required = false) String status) {
        return Result.success(orderService.getOrderList(UserContext.getUserId(), status));
    }

    @GetMapping("/{id}")
    public Result<OrderDetailVO> detail(@PathVariable Long id) {
        return Result.success(orderService.getOrderDetail(id, UserContext.getUserId()));
    }

    @PostMapping("/{id}/pay")
    public Result<PayParamsVO> pay(@PathVariable Long id) {
        return Result.success(orderService.pay(id, UserContext.getUserId()));
    }

    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        orderService.cancelOrder(id, UserContext.getUserId());
        return Result.success();
    }

    @GetMapping("/{id}/pay/status")
    public Result<Map<String, Object>> payStatus(@PathVariable Long id) {
        return Result.success(orderService.getPayStatus(id, UserContext.getUserId()));
    }
}
