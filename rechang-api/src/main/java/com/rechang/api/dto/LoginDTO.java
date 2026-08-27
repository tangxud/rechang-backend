package com.rechang.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginDTO {
    @NotBlank(message = "微信登录 code 不能为空")
    private String code;
    private String nickname;
    private String avatarUrl;
}
