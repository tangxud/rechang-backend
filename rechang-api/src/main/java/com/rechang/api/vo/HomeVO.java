package com.rechang.api.vo;

import lombok.Data;
import java.util.Date;
import java.util.List;

@Data
public class HomeVO {
    private long serverTime;
    private List<BannerItem> banners;
    private List<UpcomingItem> upcoming;
    private List<PerformanceCardVO> recommendations;
    private List<PerformanceCardVO> hotList;

    @Data
    public static class BannerItem {
        private Long id;
        private String title;
        private String imageUrl;
        private String linkType;
        private Long linkId;
    }

    @Data
    public static class UpcomingItem {
        private Long performanceId;
        private String name;
        private String posterUrl;
        private Date startAt;
        private Date saleStartTime;
        private long countdownSeconds;
        private String city;
        private String venueName;
    }
}
