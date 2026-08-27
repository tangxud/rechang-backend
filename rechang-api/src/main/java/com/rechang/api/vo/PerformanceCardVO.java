package com.rechang.api.vo;

import lombok.Data;
import java.util.Date;

@Data
public class PerformanceCardVO {
    private Long performanceId;
    private String name;
    private String posterUrl;
    private Date startAt;
    private Date endAt;
    private String city;
    private String venueName;
    private Integer minPrice;
    private String showType;
    private String showForm;
    private String publishStatus;
    private Boolean isHotSale;
}
