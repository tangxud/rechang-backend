package com.rechang.api.vo;

import lombok.Data;

@Data
public class LoginVO {
    private String token;
    private long expiresIn;
    private Long userId;
    private boolean isNewUser;
    private boolean needPhone;
    private boolean needRealname;
}
