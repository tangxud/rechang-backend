package com.rechang.api.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ReviewSummaryVO {
    private BigDecimal avgRating;
    private Integer totalCount;
    private List<TagCount> topTags;

    @Data
    public static class TagCount {
        private String tag;
        private Integer count;

        public TagCount() {}

        public TagCount(String tag, Integer count) {
            this.tag = tag;
            this.count = count;
        }
    }
}
