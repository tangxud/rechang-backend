package com.rechang.api.vo;

import lombok.Data;

@Data
public class PayParamsVO {

    private String timeStamp;
    private String nonceStr;
    private String packageStr;
    private String signType;
    private String paySign;
}
