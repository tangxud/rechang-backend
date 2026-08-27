package com.rechang.api.vo;

import lombok.Data;

import java.util.Date;

@Data
public class OrderVO {

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
    private Date createTime;
    private String performanceName;
    private String posterUrl;
    private Date startAt;
    private String venueName;
}
