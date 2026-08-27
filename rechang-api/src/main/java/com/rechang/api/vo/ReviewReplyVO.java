package com.rechang.api.vo;

import lombok.Data;

@Data
public class ReviewReplyVO {
    private Long replyId;
    private Long reviewId;
    private String content;
    private String userNickname;
    private String userAvatar;
    private Boolean isMine;
    private String createdAt;
}
