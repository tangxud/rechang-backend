package com.rechang.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("performance_price_zone")
public class PerformancePriceZone {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("performance_id")
    private Long performanceId;

    private String region;

    @TableField("zone_name")
    private String zoneName;

    private Integer price;

    @TableField("total_count")
    private Integer totalCount;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;
}
