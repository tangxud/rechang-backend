package com.rechang.api.controller.c;

import com.rechang.api.dto.AttendeeDTO;
import com.rechang.api.security.UserContext;
import com.rechang.api.service.AttendeeService;
import com.rechang.api.vo.AttendeeVO;
import com.rechang.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/attendees")
@RequiredArgsConstructor
public class AttendeeController {

    private final AttendeeService attendeeService;

    @GetMapping
    public Result<List<AttendeeVO>> list() {
        return Result.success(attendeeService.list(UserContext.getUserId()));
    }

    @PostMapping
    public Result<AttendeeVO> create(@Valid @RequestBody AttendeeDTO dto) {
        return Result.success(attendeeService.create(dto, UserContext.getUserId()));
    }

    @PutMapping("/{id}")
    public Result<AttendeeVO> update(@PathVariable Long id, @Valid @RequestBody AttendeeDTO dto) {
        return Result.success(attendeeService.update(id, dto, UserContext.getUserId()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        attendeeService.delete(id, UserContext.getUserId());
        return Result.success();
    }
}
