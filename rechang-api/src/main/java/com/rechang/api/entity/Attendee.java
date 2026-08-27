package com.rechang.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("attendee")
public class Attendee {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("attendee_name")
    private String attendeeName;

    @TableField("id_card_hash")
    private String idCardHash;

    @TableField("id_card_masked")
    private String idCardMasked;

    @TableField("is_self")
    private Integer isSelf;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;
}
