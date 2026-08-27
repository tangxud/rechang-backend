package com.rechang.api.vo;

import lombok.Data;

import java.util.List;

@Data
public class ReviewReplyListVO {
    private List<ReviewReplyVO> list;
    private Integer page;
    private Integer size;
    private Long total;
}
