package com.rechang.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("invoice")
public class Invoice {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("order_id")
    private Long orderId;

    @TableField("title_type")
    private String titleType;

    @TableField("invoice_title")
    private String invoiceTitle;

    @TableField("tax_no")
    private String taxNo;

    private String email;

    private Integer amount;

    private String status;

    @TableField("invoice_url")
    private String invoiceUrl;

    @TableField("invoice_no")
    private String invoiceNo;

    @TableField("issued_at")
    private Date issuedAt;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;
}
