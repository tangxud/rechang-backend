package com.rechang.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("seat")
public class Seat {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("venue_id")
    private Long venueId;

    private String region;

    @TableField("row_label")
    private String rowLabel;

    @TableField("col_label")
    private String colLabel;

    @TableField("seat_label")
    private String seatLabel;

    private String status;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;
}
