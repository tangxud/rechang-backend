package com.rechang.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class InvoiceDTO {

    @NotBlank(message = "抬头类型不能为空")
    private String titleType;

    @NotBlank(message = "发票抬头不能为空")
    private String invoiceTitle;

    private String taxNo;

    @NotBlank(message = "接收邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;
}
