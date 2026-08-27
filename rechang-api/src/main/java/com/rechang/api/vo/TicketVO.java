package com.rechang.api.vo;

import lombok.Data;

import java.util.Date;

@Data
public class TicketVO {
    private Long id;
    private Long orderId;
    private Long performanceId;
    private Long seatId;
    private String seatLabel;
    private Integer faceAmount;
    private Long ownerUserId;
    private String status;
    private Integer transferCount;
    private String attendeeIdCardMasked;
    private Date usedAt;
    private String performanceName;
    private String posterUrl;
    private Date startAt;
    private Date endAt;
    private String venueName;
    private String zoneName;
    private String orderStatus;
    private Long reviewId;
}
