package com.rechang.api.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("review_helpful")
public class ReviewHelpful {

    @TableField("review_id")
    private Long reviewId;

    @TableField("user_id")
    private Long userId;

    @TableField("create_time")
    private Date createTime;
}
