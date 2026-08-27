package com.rechang.api.vo;

import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class OrderDetailVO {

    private Long id;
    private String orderNo;
    private Long userId;
    private Long performanceId;
    private Integer totalAmount;
    private Integer refundedAmount;
    private String payChannel;
    private String source;
    private String status;
    private Date paidAt;
    private Date completedAt;
    private Date reviewedAt;
    private Date createTime;
    private Long reviewId;
    private String performanceName;
    private String posterUrl;
    private Date startAt;
    private String venueName;

    private List<TicketSimpleVO> tickets;
    private List<TimelineItem> timeline;

    @Data
    public static class TicketSimpleVO {
        private Long id;
        private String seatLabel;
        private Integer faceAmount;
        private String status;
        private String attendeeName;
    }

    @Data
    public static class TimelineItem {
        private String status;
        private Date time;
        private String label;
    }
}
