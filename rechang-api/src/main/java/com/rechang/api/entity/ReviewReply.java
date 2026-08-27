package com.rechang.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("review_reply")
public class ReviewReply {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("review_id")
    private Long reviewId;

    @TableField("user_id")
    private Long userId;

    private String content;

    private String status;

    @TableField("create_time")
    private Date createTime;
}
