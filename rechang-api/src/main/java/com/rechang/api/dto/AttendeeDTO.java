package com.rechang.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class AttendeeDTO {
    @NotBlank(message = "观演人姓名不能为空")
    private String name;
    @Pattern(regexp = "^\\d{17}[\\dXx]$", message = "身份证号格式不正确")
    private String idCardNo;
}
