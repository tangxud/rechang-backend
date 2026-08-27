package com.rechang.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("banner")
public class Banner {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    @TableField("cover_url")
    private String coverUrl;

    @TableField("link_type")
    private String linkType;

    @TableField("link_target")
    private String linkTarget;

    @TableField("sort_order")
    private Integer sortOrder;

    private String status;

    @TableField("start_time")
    private Date startTime;

    @TableField("end_time")
    private Date endTime;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;
}
