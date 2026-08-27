package com.rechang.api.controller.c;

import com.rechang.api.security.UserContext;
import com.rechang.api.service.AuthService;
import com.rechang.api.vo.UserProfileVO;
import com.rechang.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class ProfileController {

    private final AuthService authService;

    @GetMapping("/profile")
    public Result<UserProfileVO> getProfile() {
        return Result.success(authService.getUserProfile(UserContext.getUserId()));
    }

    @PutMapping("/profile")
    public Result<UserProfileVO> updateProfile(@RequestBody Map<String, String> body) {
        return Result.success(authService.updateProfile(
            UserContext.getUserId(),
            body.get("nickname"),
            body.get("avatar_url")
        ));
    }
}
