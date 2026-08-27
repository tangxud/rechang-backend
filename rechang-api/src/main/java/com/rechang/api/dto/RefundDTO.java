package com.rechang.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class RefundDTO {
    private String reason;
    private List<String> evidenceUrls;
}
