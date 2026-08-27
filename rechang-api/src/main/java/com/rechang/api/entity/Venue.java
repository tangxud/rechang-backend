package com.rechang.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("venue")
public class Venue {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("venue_name")
    private String venueName;

    @TableField("city_code")
    private String cityCode;

    private String address;

    @TableField("total_seat_count")
    private Integer totalSeatCount;

    private String status;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;
}
