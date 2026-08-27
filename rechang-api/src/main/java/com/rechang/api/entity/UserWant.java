package com.rechang.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("user_want")
public class UserWant {

    @TableId(type = IdType.INPUT)
    @TableField("user_id")
    private Long userId;

    @TableField("performance_id")
    private Long performanceId;

    @TableField("create_time")
    private Date createTime;
}
