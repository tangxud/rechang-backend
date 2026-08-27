package com.rechang.api.controller.c;

import com.rechang.api.service.SeatService;
import com.rechang.api.vo.SeatMapVO;
import com.rechang.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shows")
@RequiredArgsConstructor
public class SeatController {

    private final SeatService seatService;

    @GetMapping("/{id}/seats")
    public Result<SeatMapVO> getSeatMap(@PathVariable Long id) {
        return Result.success(seatService.getSeatMap(id));
    }
}
