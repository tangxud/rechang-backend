package com.rechang.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class ReviewSubmitDTO {
    private Long performanceId;
    private Integer rating;
    private List<String> tags;
    private String content;
    private List<String> images;
    private Boolean isAnonymous;
}
