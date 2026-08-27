package com.rechang.api.vo;

import lombok.Data;

@Data
public class RefundPreviewVO {
    private Long ticketId;
    private String seatLabel;
    private Integer ticketAmount;
    private String stage;
    private Integer feeRate;
    private Integer feeAmount;
    private Integer refundAmount;
    private Boolean refundable;
    private Boolean forceMajeureAvailable;
    private String estimatedArrival;
    private String stageDesc;
}
