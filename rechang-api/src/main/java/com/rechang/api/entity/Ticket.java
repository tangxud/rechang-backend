package com.rechang.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ticket")
public class Ticket {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("order_id")
    private Long orderId;

    @TableField("performance_id")
    private Long performanceId;

    @TableField("seat_id")
    private Long seatId;

    @TableField("face_amount")
    private Integer faceAmount;

    @TableField("owner_user_id")
    private Long ownerUserId;

    @TableField("original_user_id")
    private Long originalUserId;

    private String status;

    @TableField("transfer_token")
    private String transferToken;

    @TableField("transfer_count")
    private Integer transferCount;

    @TableField("attendee_id_card_hash")
    private String attendeeIdCardHash;

    @TableField("face_verified")
    private Integer faceVerified;

    @TableField("used_at")
    private Date usedAt;

    @TableField("transferred_at")
    private Date transferredAt;

    @TableField("create_time")
    private Date createTime;
}
