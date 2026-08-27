-- ============================================================
-- Rechang 热场票务平台 · 数据库建表脚本
-- 版本: v1.3 (依据 database_design.md)
-- 规范: mysql_design_spec.md (utf8mb4 / InnoDB / TIMESTAMP / INT分)
-- ============================================================

SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;

CREATE DATABASE IF NOT EXISTS rechang DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE rechang;

-- ============================================================
-- 演出域 (6 tables)
-- ============================================================

-- 1. artist 艺人信息
CREATE TABLE artist (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    artist_name   VARCHAR(100) NOT NULL              COMMENT '艺人名称',
    avatar_url    VARCHAR(500) NOT NULL DEFAULT ''   COMMENT '艺人头像URL',
    description   VARCHAR(2000) NOT NULL DEFAULT ''  COMMENT '艺人简介',
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE启用/DISABLED停用',
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX ix_artist_name (artist_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='艺人信息';

-- 2. performance 演出主表
CREATE TABLE performance (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    perf_name             VARCHAR(200) NOT NULL              COMMENT '演出名称',
    artist_id             BIGINT       NOT NULL DEFAULT 0    COMMENT '关联艺人ID',
    perf_type             VARCHAR(20)  NOT NULL              COMMENT '内容类型：CONCERT/DRAMA/SPORT/EXHIBITION',
    show_form             VARCHAR(20)  NOT NULL              COMMENT '演出形态：TOUR/RESIDENT/PREMIERE/EVENT',
    tour_id               VARCHAR(64)  NOT NULL DEFAULT ''    COMMENT '巡演唯一标识',
    tour_name             VARCHAR(200) NOT NULL DEFAULT ''   COMMENT '巡演名称',
    tour_sequence         INT          NOT NULL DEFAULT 0    COMMENT '当前第几站',
    city_code             VARCHAR(20)  NOT NULL              COMMENT '城市编码',
    venue_id              BIGINT       NOT NULL DEFAULT 0    COMMENT '场馆ID',
    start_date            DATE         NOT NULL              COMMENT '演出开始日期',
    start_at              TIMESTAMP    NOT NULL              COMMENT '演出开始时刻',
    end_at                TIMESTAMP    NOT NULL              COMMENT '演出结束时刻',
    sale_start_time       TIMESTAMP    NULL                  COMMENT '开售时间',
    sale_end_time         TIMESTAMP    NULL                  COMMENT '停售时间',
    poster_url            VARCHAR(500) NOT NULL DEFAULT ''   COMMENT '演出海报URL',
    description           VARCHAR(2000) NOT NULL DEFAULT ''  COMMENT '演出介绍',
    min_price             INT          NOT NULL DEFAULT 0    COMMENT '最低票价（分）',
    purchase_limit_per_id INT          NOT NULL DEFAULT 4    COMMENT '每身份证每场限购张数',
    is_strong_real_name   TINYINT      NOT NULL DEFAULT 1    COMMENT '是否强实名：1是 0否',
    is_hot_sale           TINYINT      NOT NULL DEFAULT 0    COMMENT '是否热门演出：1是 0否',
    publish_status        VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ON_SALE/OFF_SHELF',
    create_time           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX ix_tour_id (tour_id),
    INDEX ix_artist_id (artist_id),
    INDEX ix_city_type (city_code, perf_type),
    INDEX ix_status_date (publish_status, start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演出主表';

-- 3. venue 场馆信息
CREATE TABLE venue (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    venue_name       VARCHAR(100) NOT NULL              COMMENT '场馆名称',
    city_code        VARCHAR(20)  NOT NULL              COMMENT '城市编码',
    address          VARCHAR(200) NOT NULL DEFAULT ''   COMMENT '场馆地址',
    total_seat_count INT          NOT NULL DEFAULT 0    COMMENT '总座位数',
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX ix_city (city_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='场馆信息';

-- 4. seat 场馆物理座位
CREATE TABLE seat (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    venue_id    BIGINT      NOT NULL              COMMENT '场馆ID',
    region      VARCHAR(32) NOT NULL              COMMENT '区域名称',
    row_label   VARCHAR(8)  NOT NULL              COMMENT '排号标签',
    col_label   VARCHAR(8)  NOT NULL              COMMENT '座号标签',
    seat_label  VARCHAR(16) NOT NULL DEFAULT ''   COMMENT '完整显示标签',
    status      VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE/DISABLED',
    create_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX ix_venue_region (venue_id, region)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='场馆物理座位';

-- 5. performance_price_zone 演出票价区域
CREATE TABLE performance_price_zone (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    performance_id  BIGINT      NOT NULL              COMMENT '演出ID',
    region          VARCHAR(32) NOT NULL              COMMENT '区域名称（与seat.region对应）',
    zone_name       VARCHAR(32) NOT NULL DEFAULT ''   COMMENT '票档名',
    price           INT         NOT NULL              COMMENT '票价（分）',
    total_count     INT         NOT NULL              COMMENT '该区域总座位数',
    create_time     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_perf_region (performance_id, region),
    INDEX ix_perf (performance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演出票价区域配置';

-- 6. banner 首页Banner
CREATE TABLE banner (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    title        VARCHAR(100) NOT NULL              COMMENT 'Banner标题',
    cover_url    VARCHAR(500) NOT NULL              COMMENT '封面图URL',
    link_type    VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT 'PERFORMANCE/URL/NONE',
    link_target  VARCHAR(200) NOT NULL DEFAULT ''   COMMENT '跳转目标',
    sort_order   INT          NOT NULL DEFAULT 0    COMMENT '排序权重',
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
    start_time   TIMESTAMP    NULL                  COMMENT '生效时间',
    end_time     TIMESTAMP    NULL                  COMMENT '失效时间',
    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX ix_status_sort (status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='首页Banner';

-- ============================================================
-- 用户域 (4 tables)
-- ============================================================

-- 7. user C端用户
CREATE TABLE user (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    openid           VARCHAR(64)  NOT NULL              COMMENT '微信openid',
    unionid          VARCHAR(64)  NOT NULL DEFAULT ''   COMMENT '微信unionid',
    phone            VARCHAR(20)  NOT NULL DEFAULT ''   COMMENT '手机号',
    nickname         VARCHAR(45)  NOT NULL DEFAULT ''   COMMENT '昵称',
    avatar_url       VARCHAR(500) NOT NULL DEFAULT ''   COMMENT '头像URL',
    realname_status  VARCHAR(16)  NOT NULL DEFAULT 'UNVERIFIED' COMMENT 'UNVERIFIED/VERIFIED/REVIEWING',
    realname_time    TIMESTAMP    NULL                  COMMENT '实名认证时间',
    register_ip      INT          NOT NULL DEFAULT 0    COMMENT '注册IP（inet_aton存储）',
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_openid (openid),
    INDEX ix_phone (phone),
    INDEX ix_realname_status (realname_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='C端用户';

-- 8. attendee 常用观演人
CREATE TABLE attendee (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         BIGINT      NOT NULL              COMMENT '所属用户ID',
    attendee_name   VARCHAR(32) NOT NULL              COMMENT '观演人姓名',
    id_card_hash    VARCHAR(64) NOT NULL              COMMENT '身份证号SHA-256哈希',
    id_card_masked  VARCHAR(20) NOT NULL DEFAULT ''   COMMENT '身份证脱敏显示',
    is_self         TINYINT     NOT NULL DEFAULT 0    COMMENT '是否本人：1是 0否',
    create_time     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_idcard (user_id, id_card_hash),
    INDEX ix_user (user_id),
    INDEX ix_idcard (id_card_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='常用观演人';

-- 9. user_risk_level 用户风控等级
CREATE TABLE user_risk_level (
    user_id      BIGINT      NOT NULL,
    risk_level   VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/OBSERVED/LIMITED/BANNED',
    reason       VARCHAR(500) NOT NULL DEFAULT ''     COMMENT '触发原因',
    create_time  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户风控等级';

-- 10. risk_control_record 风控记录
CREATE TABLE risk_control_record (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    user_id            BIGINT       NOT NULL              COMMENT '用户ID',
    rule_type          VARCHAR(32)  NOT NULL              COMMENT 'IP_FREQ/DEVICE_FREQ/ACCOUNT_FREQ/ID_CARD_FREQ/BEHAVIOR_PATTERN',
    rule_detail        VARCHAR(500) NOT NULL DEFAULT ''   COMMENT '触发详情',
    action             VARCHAR(32)  NOT NULL              COMMENT 'PASS/OBSERVE/REQUIRE_REVIEW/BLOCK',
    ip                 INT          NOT NULL DEFAULT 0    COMMENT '触发IP',
    device_fingerprint  VARCHAR(128) NOT NULL DEFAULT ''   COMMENT '设备指纹',
    create_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX ix_user (user_id),
    INDEX ix_rule_type (rule_type),
    INDEX ix_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控记录';

-- ============================================================
-- 交易域 (4 tables)
-- ============================================================

-- 11. order 订单
CREATE TABLE `order` (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    order_no              VARCHAR(32)  NOT NULL              COMMENT '订单号',
    user_id               BIGINT       NOT NULL              COMMENT '下单用户ID',
    performance_id        BIGINT       NOT NULL              COMMENT '演出ID',
    total_amount          INT          NOT NULL DEFAULT 0     COMMENT '订单原始总金额（分）',
    refunded_amount       INT          NOT NULL DEFAULT 0     COMMENT '累计已退款金额（分）',
    pay_channel           VARCHAR(32)  NOT NULL DEFAULT ''   COMMENT 'WECHAT/ALIPAY/UNIONPAY',
    source                VARCHAR(32)  NOT NULL DEFAULT 'DIRECT' COMMENT 'DIRECT/TRANSFER',
    original_order_id     BIGINT       NOT NULL DEFAULT 0    COMMENT '原购买者订单ID',
    original_pay_order_id BIGINT       NOT NULL DEFAULT 0    COMMENT '实际支付订单ID',
    status                VARCHAR(32)  NOT NULL DEFAULT 'PENDING_PAY' COMMENT 'PENDING_PAY/ISSUED/CANCELLED/REFUNDED/TRANSFERRED/ATTENDED/REVIEWED',
    version               INT          NOT NULL DEFAULT 0    COMMENT '乐观锁版本号',
    completed_at          TIMESTAMP    NULL                  COMMENT '首张票核销时间',
    paid_at               TIMESTAMP    NULL                  COMMENT '支付时间',
    refunded_at           TIMESTAMP    NULL                  COMMENT '退款完成时间',
    cancelled_at          TIMESTAMP    NULL                  COMMENT '取消时间',
    cancel_reason         VARCHAR(32)  NULL                  COMMENT 'TIMEOUT/USER/RISK',
    transferred_at        TIMESTAMP    NULL                  COMMENT '转赠时间',
    create_time           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    INDEX ix_user_status (user_id, status),
    INDEX ix_perf_status (performance_id, status),
    INDEX ix_completed_at (completed_at),
    INDEX ix_original_pay (original_pay_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单';

-- 12. ticket 电子票
CREATE TABLE ticket (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    order_id              BIGINT       NOT NULL              COMMENT '所属订单ID',
    performance_id        BIGINT       NOT NULL              COMMENT '演出ID',
    seat_id               BIGINT       NULL                  COMMENT '座位ID（站票为NULL）',
    face_amount           INT          NOT NULL DEFAULT 0     COMMENT '票面金额（分）',
    owner_user_id         BIGINT       NOT NULL              COMMENT '当前持有人ID',
    original_user_id      BIGINT       NOT NULL              COMMENT '原购票人ID',
    status                VARCHAR(32)  NOT NULL DEFAULT 'USABLE' COMMENT 'USABLE/USED/TRANSFERRED/REFUNDED/EXPIRED',
    transfer_token        VARCHAR(64)  NOT NULL DEFAULT ''   COMMENT '转赠Token',
    transfer_count        INT          NOT NULL DEFAULT 0    COMMENT '转赠链路深度',
    attendee_id_card_hash VARCHAR(64)  NOT NULL              COMMENT '观演人身份证哈希',
    face_verified         TINYINT      NOT NULL DEFAULT 0    COMMENT '入场人脸核验状态',
    used_at               TIMESTAMP    NULL                  COMMENT '闸机核销时间',
    transferred_at        TIMESTAMP    NULL                  COMMENT '转赠时间',
    create_time           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_seat (order_id, seat_id),
    INDEX ix_owner_status (owner_user_id, status),
    INDEX ix_transfer_token (transfer_token),
    INDEX ix_perf_status (performance_id, status),
    INDEX ix_idcard_perf (attendee_id_card_hash, performance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='电子票';

-- 13. refund_record 退款记录
CREATE TABLE refund_record (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    refund_no       VARCHAR(32)  NOT NULL              COMMENT '业务退款单号',
    order_id        BIGINT       NOT NULL              COMMENT '关联订单ID',
    ticket_id       BIGINT       NOT NULL              COMMENT '关联退票ID',
    user_id         BIGINT       NOT NULL              COMMENT '退款人',
    refund_type     VARCHAR(32)  NOT NULL              COMMENT 'PERSONAL/FORCE_MAJEURE/SHOW_CANCELLED/SHOW_POSTPONED/SHOW_VENUE_CHANGED',
    ticket_amount   INT          NOT NULL              COMMENT '该票票面金额（分）',
    fee_rate        INT          NOT NULL DEFAULT 0   COMMENT '手续费比例（千分比）',
    fee_amount      INT          NOT NULL DEFAULT 0   COMMENT '手续费金额（分）',
    refund_amount   INT          NOT NULL              COMMENT '实退金额（分）',
    pay_channel     VARCHAR(32)  NOT NULL              COMMENT '退款渠道',
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/SUCCESS/FAILED',
    channel_refund_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '支付渠道退款单号',
    evidence_urls   VARCHAR(2000) NOT NULL DEFAULT ''  COMMENT '不可抗力凭证图片URL',
    reviewed_by     BIGINT       NOT NULL DEFAULT 0    COMMENT '审核人ID',
    reviewed_at     TIMESTAMP    NULL                  COMMENT '审核时间',
    review_remark   VARCHAR(500) NOT NULL DEFAULT ''   COMMENT '审核备注',
    refunded_at     TIMESTAMP    NULL                  COMMENT '退款到账时间',
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_no (refund_no),
    INDEX ix_order (order_id),
    INDEX ix_ticket (ticket_id),
    INDEX ix_user (user_id),
    INDEX ix_status (status),
    INDEX ix_type_status (refund_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款记录';

-- 14. invoice 电子发票
CREATE TABLE invoice (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL              COMMENT '用户ID',
    order_id     BIGINT       NOT NULL              COMMENT '关联订单',
    title_type   VARCHAR(16)  NOT NULL              COMMENT 'PERSONAL/ENTERPRISE',
    invoice_title VARCHAR(200) NOT NULL              COMMENT '发票抬头',
    tax_no       VARCHAR(32)  NOT NULL DEFAULT ''   COMMENT '税号',
    email        VARCHAR(64)  NOT NULL              COMMENT '接收邮箱',
    amount       INT          NOT NULL              COMMENT '开票金额（分）',
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ISSUED/VOIDED',
    invoice_url  VARCHAR(500) NOT NULL DEFAULT ''   COMMENT 'PDF文件URL',
    invoice_no   VARCHAR(32)  NULL DEFAULT NULL    COMMENT '发票编号',
    issued_at    TIMESTAMP    NULL                  COMMENT '开具时间',
    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order (order_id),
    UNIQUE KEY uk_invoice_no (invoice_no),
    INDEX ix_user (user_id),
    INDEX ix_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='电子发票';

-- ============================================================
-- 评价域 (6 tables)
-- ============================================================

-- 15. performance_review 演出评价
CREATE TABLE performance_review (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    group_id                VARCHAR(64)  NOT NULL              COMMENT '聚合维度',
    attended_performance_id BIGINT       NOT NULL              COMMENT '用户观演的场次ID',
    user_id                 BIGINT       NOT NULL              COMMENT '评价用户ID',
    order_id                BIGINT       NOT NULL              COMMENT '关联订单',
    rating                  TINYINT      NOT NULL              COMMENT '评分1-5星',
    tags                    VARCHAR(500) NOT NULL DEFAULT ''   COMMENT '标签JSON数组',
    content                 VARCHAR(2000) NOT NULL DEFAULT ''  COMMENT '评价正文',
    images                  VARCHAR(2000) NOT NULL DEFAULT ''   COMMENT '图片URL JSON数组',
    site_city               VARCHAR(50)  NOT NULL DEFAULT ''   COMMENT '站点城市',
    helpful_count           INT          NOT NULL DEFAULT 0    COMMENT '点赞数',
    reply_count             INT          NOT NULL DEFAULT 0    COMMENT '回复数',
    is_anonymous            TINYINT      NOT NULL DEFAULT 0    COMMENT '是否匿名',
    status                  VARCHAR(16)  NOT NULL DEFAULT 'VISIBLE' COMMENT 'VISIBLE/HIDDEN/DELETED',
    create_time             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order (order_id),
    INDEX ix_group_status (group_id, status),
    INDEX ix_perf (attended_performance_id),
    INDEX ix_user (user_id),
    INDEX ix_status_helpful (status, helpful_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演出评价';

-- 16. review_summary 评价聚合
CREATE TABLE review_summary (
    group_id       VARCHAR(64)  NOT NULL,
    total_reviews  INT          NOT NULL DEFAULT 0,
    avg_rating     DECIMAL(2,1) NOT NULL DEFAULT 0.0,
    top_tags       VARCHAR(500) NOT NULL DEFAULT '',
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评价聚合';

-- 17. user_want 想看
CREATE TABLE user_want (
    user_id        BIGINT    NOT NULL,
    performance_id BIGINT    NOT NULL,
    create_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, performance_id),
    INDEX ix_perf (performance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户想看';

-- 18. review_helpful 评价点赞
CREATE TABLE review_helpful (
    review_id   BIGINT    NOT NULL,
    user_id     BIGINT    NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (review_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评价点赞防重';

-- 19. review_reply 评价回复
CREATE TABLE review_reply (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    review_id   BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    content     VARCHAR(500) NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'VISIBLE',
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX ix_review (review_id),
    INDEX ix_user (user_id),
    INDEX ix_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评价回复';

-- 20. review_report 评价举报
CREATE TABLE review_report (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    review_id         BIGINT       NOT NULL,
    reporter_user_id  BIGINT       NOT NULL,
    report_type       VARCHAR(32)  NOT NULL              COMMENT 'SPAM/ABUSE/FALSE/OTHER',
    reason            VARCHAR(500) NOT NULL DEFAULT '',
    status            VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CONFIRMED/REJECTED',
    create_time       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_reporter (review_id, reporter_user_id),
    INDEX ix_reporter (reporter_user_id),
    INDEX ix_status_created (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评价举报';

-- ============================================================
-- 消息域 (2 tables)
-- ============================================================

-- 21. message 消息通知
CREATE TABLE message (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    msg_type     VARCHAR(16)  NOT NULL              COMMENT 'SYSTEM/ORDER/ACTIVITY/SERVICE',
    title        VARCHAR(100) NOT NULL,
    content      VARCHAR(500) NOT NULL DEFAULT '',
    ref_type     VARCHAR(32)  NOT NULL DEFAULT '',
    ref_id       BIGINT       NOT NULL DEFAULT 0,
    is_read      TINYINT      NOT NULL DEFAULT 0,
    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX ix_user_type_read (user_id, msg_type, is_read),
    INDEX ix_user_created (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息通知';

-- 22. user_subscription 开票订阅
CREATE TABLE user_subscription (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         BIGINT      NOT NULL,
    performance_id  BIGINT      NOT NULL,
    sub_type        VARCHAR(32) NOT NULL              COMMENT 'ON_SALE/REVIEW',
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    create_time     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_perf_type (user_id, performance_id, sub_type),
    INDEX ix_perf_type_status (performance_id, sub_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='开票订阅提醒';

-- ============================================================
-- B端 RBAC (5 tables)
-- ============================================================

-- 23. admin_user 管理员账号
CREATE TABLE admin_user (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    username       VARCHAR(32)  NOT NULL,
    password_hash  VARCHAR(128) NOT NULL,
    real_name      VARCHAR(32)  NOT NULL,
    phone          VARCHAR(20)  NOT NULL DEFAULT '',
    email          VARCHAR(64)  NOT NULL DEFAULT '',
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    last_login_at  TIMESTAMP    NULL,
    last_login_ip  INT          NOT NULL DEFAULT 0,
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='B端管理员账号';

-- 24. admin_role 角色
CREATE TABLE admin_role (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    role_code    VARCHAR(32)  NOT NULL,
    role_name    VARCHAR(32)  NOT NULL,
    description  VARCHAR(200) NOT NULL DEFAULT '',
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='B端角色';

-- 25. admin_permission 权限
CREATE TABLE admin_permission (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    perm_code    VARCHAR(64)  NOT NULL,
    perm_name    VARCHAR(64)  NOT NULL,
    perm_type    VARCHAR(16)  NOT NULL              COMMENT 'MENU/BUTTON/API',
    resource     VARCHAR(64)  NOT NULL DEFAULT '',
    action       VARCHAR(16)  NOT NULL DEFAULT '',
    description  VARCHAR(200) NOT NULL DEFAULT '',
    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_perm_code (perm_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='B端权限定义';

-- 26. admin_role_permission 角色-权限
CREATE TABLE admin_role_permission (
    id            BIGINT    NOT NULL AUTO_INCREMENT,
    role_id       BIGINT    NOT NULL,
    permission_id BIGINT    NOT NULL,
    create_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_perm (role_id, permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-权限关联';

-- 27. admin_user_role 用户-角色
CREATE TABLE admin_user_role (
    id          BIGINT    NOT NULL AUTO_INCREMENT,
    user_id     BIGINT    NOT NULL,
    role_id     BIGINT    NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色关联';

-- ============================================================
-- 工单域 (2 tables)
-- ============================================================

-- 28. work_order 客服工单
CREATE TABLE work_order (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    wo_no           VARCHAR(32)  NOT NULL,
    wo_type         VARCHAR(32)  NOT NULL              COMMENT 'REFUND_APPEAL/RISK_APPEAL/REVIEW_APPEAL/REALNAME_REVIEW',
    user_id         BIGINT       NOT NULL,
    ref_id          BIGINT       NOT NULL DEFAULT 0,
    ref_type        VARCHAR(32)  NOT NULL DEFAULT '',
    title           VARCHAR(200) NOT NULL,
    description     VARCHAR(2000) NOT NULL DEFAULT '',
    evidence_urls   VARCHAR(2000) NOT NULL DEFAULT '',
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/RESOLVED/REJECTED',
    priority        VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',
    sla_due_time    TIMESTAMP    NULL,
    handler_id      BIGINT       NOT NULL DEFAULT 0,
    handler_remark  VARCHAR(500) NOT NULL DEFAULT '',
    ref_parent_id   BIGINT       NOT NULL DEFAULT 0,
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at    TIMESTAMP    NULL,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wo_no (wo_no),
    INDEX ix_user_status (user_id, status),
    INDEX ix_type_status (wo_type, status),
    INDEX ix_handler (handler_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服工单';

-- 29. work_order_log 工单处理记录
CREATE TABLE work_order_log (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    work_order_id  BIGINT       NOT NULL,
    operator_id    BIGINT       NOT NULL,
    operator_type  VARCHAR(16)  NOT NULL              COMMENT 'USER/ADMIN/SYSTEM',
    action         VARCHAR(32)  NOT NULL              COMMENT 'CREATE/ASSIGN/PROCESS/RESOLVE/REJECT/SLA_TIMEOUT',
    remark         VARCHAR(500) NOT NULL DEFAULT '',
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX ix_work_order (work_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单处理记录';
