package com.rechang.api.vo;

import lombok.Data;

import java.util.Date;

@Data
public class TransferPreviewVO {
    private String transferToken;
    private Long performanceId;
    private String perfName;
    private String posterUrl;
    private Date startAt;
    private String venueName;
    private String seatLabel;
    private Integer faceAmount;
    private String giverNickname;
    private Date expireAt;
}
