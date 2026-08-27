package com.rechang.api.vo;

import lombok.Data;

import java.util.Date;

@Data
public class RefundRecordVO {
    private Long id;
    private String refundNo;
    private Long orderId;
    private Long ticketId;
    private String refundType;
    private Integer ticketAmount;
    private Integer feeRate;
    private Integer feeAmount;
    private Integer refundAmount;
    private String payChannel;
    private String status;
    private String evidenceUrls;
    private Date refundedAt;
    private Date createTime;
    private String seatLabel;
    private String performanceName;
}
