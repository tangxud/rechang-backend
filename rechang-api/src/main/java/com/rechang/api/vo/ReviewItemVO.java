package com.rechang.api.vo;

import lombok.Data;

import java.util.List;

@Data
public class ReviewItemVO {
    private Long reviewId;
    private String siteCity;
    private Integer rating;
    private String content;
    private List<String> images;
    private List<String> tags;
    private Integer helpfulCount;
    private Integer replyCount;
    private String userNickname;
    private String userAvatar;
    private Boolean isAnonymous;
    private Boolean isHelpful;
    private Boolean isMine;
    private String createdAt;
}
