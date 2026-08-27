package com.rechang.api.vo;

import lombok.Data;

import java.util.List;

@Data
public class SeatMapVO {

    private Long performanceId;
    private String venueName;
    private Boolean isStanding;
    private List<PriceZoneInfo> priceZones;
    private List<RegionInfo> regions;

    @Data
    public static class PriceZoneInfo {
        private String region;
        private String zoneName;
        private Integer price;
        private Integer totalCount;
    }

    @Data
    public static class RegionInfo {
        private String region;
        private Integer price;
        private List<RowInfo> rows;
    }

    @Data
    public static class RowInfo {
        private String rowLabel;
        private List<SeatInfo> seats;
    }

    @Data
    public static class SeatInfo {
        private Long seatId;
        private String colLabel;
        private String seatLabel;
        private String status;
    }
}
