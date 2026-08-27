package com.rechang.api.vo;

import lombok.Data;
import java.util.Date;
import java.util.List;

@Data
public class ShowDetailVO {
    private Long performanceId;
    private String name;
    private String showType;
    private String showForm;
    private String tourId;
    private String tourName;
    private String posterUrl;
    private String description;
    private Date startAt;
    private Date endAt;
    private String city;
    private VenueInfo venue;
    private ArtistInfo artist;
    private List<PriceZoneInfo> priceZones;
    private Integer minPrice;
    private Integer maxPrice;
    private String publishStatus;
    private Date saleStartTime;
    private Date saleEndTime;
    private Boolean isHotSale;
    private Boolean isStrongRealName;
    private Integer purchaseLimitPerId;
    private int wantCount;
    private Boolean isWanted;
    private ReviewSummaryInfo reviewSummary;

    @Data
    public static class VenueInfo {
        private Long venueId;
        private String venueName;
        private String address;
    }

    @Data
    public static class ArtistInfo {
        private Long artistId;
        private String artistName;
        private String avatarUrl;
    }

    @Data
    public static class PriceZoneInfo {
        private Long zoneId;
        private String zoneName;
        private Integer price;
        private String region;
        private Integer totalCount;
    }

    @Data
    public static class ReviewSummaryInfo {
        private double avgRating;
        private int totalReviews;
        private List<String> topTags;
    }

    @Data
    public static class ReviewTagInfo {
        private String tag;
        private int count;
    }
}
