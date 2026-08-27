package com.rechang.api.vo;

import lombok.Data;

import java.util.List;

@Data
public class ReviewListVO {
    private ReviewSummaryVO summary;
    private List<ReviewItemVO> list;
    private Integer page;
    private Integer size;
    private Long total;
}
