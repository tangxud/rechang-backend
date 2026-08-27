package com.rechang.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("performance")
public class Performance {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("perf_name")
    private String perfName;

    @TableField("artist_id")
    private Long artistId;

    @TableField("perf_type")
    private String perfType;

    @TableField("show_form")
    private String showForm;

    @TableField("tour_id")
    private String tourId;

    @TableField("tour_name")
    private String tourName;

    @TableField("tour_sequence")
    private Integer tourSequence;

    @TableField("city_code")
    private String cityCode;

    @TableField("venue_id")
    private Long venueId;

    @TableField("start_date")
    private java.sql.Date startDate;

    @TableField("start_at")
    private Date startAt;

    @TableField("end_at")
    private Date endAt;

    @TableField("sale_start_time")
    private Date saleStartTime;

    @TableField("sale_end_time")
    private Date saleEndTime;

    @TableField("poster_url")
    private String posterUrl;

    private String description;

    @TableField("min_price")
    private Integer minPrice;

    @TableField("purchase_limit_per_id")
    private Integer purchaseLimitPerId;

    @TableField("is_strong_real_name")
    private Integer isStrongRealName;

    @TableField("is_hot_sale")
    private Integer isHotSale;

    @TableField("publish_status")
    private String publishStatus;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;
}
