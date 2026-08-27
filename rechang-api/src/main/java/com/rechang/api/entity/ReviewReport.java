package com.rechang.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("review_report")
public class ReviewReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("review_id")
    private Long reviewId;

    @TableField("reporter_user_id")
    private Long reporterUserId;

    @TableField("report_type")
    private String reportType;

    private String reason;

    private String status;

    @TableField("create_time")
    private Date createTime;
}
