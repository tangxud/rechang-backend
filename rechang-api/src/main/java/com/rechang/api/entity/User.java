package com.rechang.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String openid;

    private String unionid;

    private String phone;

    private String nickname;

    @TableField("avatar_url")
    private String avatarUrl;

    @TableField("realname_status")
    private String realnameStatus;

    @TableField("realname_time")
    private Date realnameTime;

    @TableField("register_ip")
    private Integer registerIp;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;
}
