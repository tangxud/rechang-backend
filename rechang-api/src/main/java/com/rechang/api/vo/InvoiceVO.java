package com.rechang.api.vo;

import lombok.Data;

import java.util.Date;

@Data
public class InvoiceVO {
    private Long id;
    private Long userId;
    private Long orderId;
    private String titleType;
    private String invoiceTitle;
    private String taxNo;
    private String email;
    private Integer amount;
    private String status;
    private String invoiceNo;
    private String invoiceUrl;
    private Date issuedAt;
    private Date createTime;
    private String orderNo;
    private String performanceName;
}
