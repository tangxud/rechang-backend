package com.rechang.api.vo;

import lombok.Data;
import java.util.Date;

@Data
public class AttendeeVO {
    private Long id;
    private String name;
    private String idCardMasked;
    private Date createdAt;
}
