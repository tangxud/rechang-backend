package com.rechang.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("`order`")
public class OrderEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("order_no")
    private String orderNo;

    @TableField("user_id")
    private Long userId;

    @TableField("performance_id")
    private Long performanceId;

    @TableField("total_amount")
    private Integer totalAmount;

    @TableField("refunded_amount")
    private Integer refundedAmount;

    @TableField("pay_channel")
    private String payChannel;

    private String source;

    @TableField("original_order_id")
    private Long originalOrderId;

    @TableField("original_pay_order_id")
    private Long originalPayOrderId;

    private String status;

    private Integer version;

    @TableField("completed_at")
    private Date completedAt;

    @TableField("paid_at")
    private Date paidAt;

    @TableField("refunded_at")
    private Date refundedAt;

    @TableField("cancelled_at")
    private Date cancelledAt;

    @TableField("cancel_reason")
    private String cancelReason;

    @TableField("reviewed_at")
    private Date reviewedAt;

    @TableField("transferred_at")
    private Date transferredAt;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;
}
