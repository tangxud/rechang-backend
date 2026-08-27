package com.rechang.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("artist")
public class Artist {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("artist_name")
    private String artistName;

    @TableField("avatar_url")
    private String avatarUrl;

    private String description;

    private String status;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;
}
