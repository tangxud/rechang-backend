package com.rechang.api.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class PayParamsVO {

    private String timeStamp;
    private String nonceStr;
    /**
     * 微信支付 SDK 要求字段名固定为 package（Java 中为关键字，字段只能叫 packageStr）
     */
    @JsonProperty("package")
    private String packageStr;
    private String signType;
    private String paySign;
}
