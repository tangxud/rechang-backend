package com.rechang.api.controller.c;

import com.rechang.api.dto.*;
import com.rechang.api.security.UserContext;
import com.rechang.api.service.AuthService;
import com.rechang.api.vo.*;
import com.rechang.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.success(authService.login(dto));
    }

    @PostMapping("/phone")
    public Result<String> bindPhone(@RequestBody PhoneBindDTO dto) {
        Long userId = UserContext.getUserId();
        return Result.success(authService.bindPhone(dto, userId));
    }

    @PostMapping("/realname")
    public Result<RealnameResultVO> submitRealname(@Valid @RequestBody RealnameDTO dto) {
        Long userId = UserContext.getUserId();
        return Result.success(authService.submitRealname(dto, userId));
    }

    @GetMapping("/realname/status")
    public Result<RealnameResultVO> getRealnameStatus() {
        return Result.success(authService.getRealnameStatus(UserContext.getUserId()));
    }
}
