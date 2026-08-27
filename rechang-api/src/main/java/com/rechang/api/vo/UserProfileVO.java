package com.rechang.api.vo;

import lombok.Data;
import java.util.Date;

@Data
public class UserProfileVO {
    private Long userId;
    private String nickname;
    private String avatarUrl;
    private String phone;
    private String realnameStatus;
    private String realName;
    private String idCardMasked;
    private Boolean needPhone;
    private Boolean needRealname;
    private Date createdAt;
}
