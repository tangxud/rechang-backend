package com.rechang.api.vo;

import lombok.Data;

@Data
public class RankingVO {
    private int rank;
    private Long performanceId;
    private String name;
    private String posterUrl;
    private Integer minPrice;
    private int hotScore;
    private String city;
    private String venueName;
}
