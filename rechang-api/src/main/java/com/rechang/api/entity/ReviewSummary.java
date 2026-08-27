package com.rechang.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("review_summary")
public class ReviewSummary {

    @TableId(type = IdType.INPUT)
    @TableField("group_id")
    private String groupId;

    @TableField("total_reviews")
    private Integer totalReviews;

    @TableField("avg_rating")
    private BigDecimal avgRating;

    @TableField("top_tags")
    private String topTags;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;
}
