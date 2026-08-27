package com.rechang.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("refund_record")
public class RefundRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("refund_no")
    private String refundNo;

    @TableField("order_id")
    private Long orderId;

    @TableField("ticket_id")
    private Long ticketId;

    @TableField("user_id")
    private Long userId;

    @TableField("refund_type")
    private String refundType;

    @TableField("ticket_amount")
    private Integer ticketAmount;

    @TableField("fee_rate")
    private Integer feeRate;

    @TableField("fee_amount")
    private Integer feeAmount;

    @TableField("refund_amount")
    private Integer refundAmount;

    @TableField("pay_channel")
    private String payChannel;

    @TableField("status")
    private String status;

    @TableField("channel_refund_no")
    private String channelRefundNo;

    @TableField("evidence_urls")
    private String evidenceUrls;

    @TableField("reviewed_by")
    private Long reviewedBy;

    @TableField("reviewed_at")
    private Date reviewedAt;

    @TableField("review_remark")
    private String reviewRemark;

    @TableField("refunded_at")
    private Date refundedAt;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;
}
