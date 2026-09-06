package com.rechang.common.result;

import lombok.Getter;

@Getter
public enum ResultCode {
    SUCCESS(200, "success", Severity.INFO),
    BAD_REQUEST(400, "请求参数错误", Severity.WARN),
    UNAUTHORIZED(401, "未登录或登录已过期", Severity.WARN),
    FORBIDDEN(403, "无权限访问", Severity.WARN),
    NOT_FOUND(404, "资源不存在", Severity.ERROR),
    CONFLICT(409, "资源冲突", Severity.WARN),
    INTERNAL_ERROR(500, "服务器内部错误", Severity.ERROR),

    USER_NOT_FOUND(1001, "用户不存在", Severity.ERROR),
    REALNAME_NOT_VERIFIED(1002, "请先完成实名认证", Severity.WARN),
    ATTENDEE_DUPLICATE(1003, "该观演人已存在", Severity.WARN),
    ID_CARD_FORMAT_ERROR(1004, "身份证号格式不正确", Severity.WARN),
    PERFORMANCE_NOT_FOUND(1005, "演出不存在或已下架", Severity.ERROR),
    LOGIN_CODE_EMPTY(1006, "微信登录 code 不能为空", Severity.WARN),
    TICKET_NOT_FOUND(1007, "票不存在", Severity.ERROR),
    TICKET_NOT_USABLE(1008, "票当前不可用", Severity.WARN),
    ORDER_NOT_FOUND(1009, "订单不存在", Severity.ERROR),
    ORDER_NOT_INVOICEABLE(1010, "订单状态不支持开票", Severity.WARN),
    INVOICE_DUPLICATE(1011, "该订单已开票", Severity.WARN),
    ORDER_STATUS_ERROR(1012, "订单状态异常", Severity.WARN),
    SEAT_LOCKED(1013, "座位已被锁定", Severity.WARN),
    SEAT_SOLD(1014, "座位已售出", Severity.WARN),
    PURCHASE_LIMIT_EXCEEDED(1015, "超过限购数量", Severity.WARN),
    TICKET_NOT_REFUNDABLE(1016, "该票当前不可退票", Severity.WARN),
    EVIDENCE_REQUIRED(1017, "不可抗力退票需上传凭证", Severity.WARN),
    REVIEW_NOT_ALLOWED(1018, "当前不可评价（需已观演且演出已结束）", Severity.WARN),
    REVIEW_ALREADY_EXISTS(1019, "该订单已评价，不可重复评价", Severity.WARN),
    REVIEW_NOT_FOUND(1020, "评价不存在或已删除", Severity.ERROR),
    REVIEW_WINDOW_EXPIRED(1021, "评价窗口已过（观演后30天内可评价）", Severity.WARN),
    REVIEW_PERMISSION_DENIED(1022, "无权操作该评价", Severity.WARN),
    REVIEW_REPORTED(1023, "该评价已被举报，暂不可操作", Severity.WARN),
    TRANSFER_TOKEN_INVALID(1024, "转赠链接已失效或已被领取", Severity.WARN),
    TRANSFER_NOT_OWNER(1025, "无权转赠他人票", Severity.WARN),
    TRANSFER_NOT_ALLOWED(1026, "当前票不可转赠", Severity.WARN),
    TRANSFER_LIMIT_EXCEEDED(1027, "每张票仅可转赠 1 次", Severity.WARN),
    ALREADY_OWNED_TICKET(1028, "您已持有该场次演出票，无法接受转赠", Severity.WARN),
    TRANSFER_IN_PROGRESS(1029, "转赠处理中，请稍后重试", Severity.WARN),
    QR_INVALID(1030, "二维码无效或已过期", Severity.WARN),
    QR_SIGNATURE_MISMATCH(1031, "二维码签名校验失败", Severity.WARN),
    FACE_VERIFY_FAILED(1032, "人脸核验未通过", Severity.WARN),
    TICKET_ALREADY_USED(1033, "该票已核销", Severity.WARN),
    TICKET_ID_CARD_USED(1034, "该身份证已核销入场，一证一票", Severity.WARN),
    WECHAT_AUTH_FAILED(1035, "微信登录失败", Severity.WARN),
    WECHAT_DECRYPT_FAILED(1036, "微信数据解密失败", Severity.WARN),
    WECHAT_PAY_FAILED(1037, "微信支付失败", Severity.WARN);

    private final int code;
    private final String message;
    private final Severity level;

    ResultCode(int code, String message, Severity level) {
        this.code = code;
        this.message = message;
        this.level = level;
    }
}
