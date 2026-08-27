package com.rechang.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("performance_review")
public class PerformanceReview {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("group_id")
    private String groupId;

    @TableField("attended_performance_id")
    private Long attendedPerformanceId;

    @TableField("user_id")
    private Long userId;

    @TableField("order_id")
    private Long orderId;

    private Integer rating;

    private String tags;

    private String content;

    private String images;

    @TableField("site_city")
    private String siteCity;

    @TableField("helpful_count")
    private Integer helpfulCount;

    @TableField("reply_count")
    private Integer replyCount;

    @TableField("is_anonymous")
    private Integer isAnonymous;

    private String status;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;
}
