package com.rechang.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RealnameDTO {
    @NotBlank(message = "身份证正面照不能为空")
    private String idCardFrontUrl;
    @NotBlank(message = "身份证背面照不能为空")
    private String idCardBackUrl;
    private String faceImageUrl;
}
