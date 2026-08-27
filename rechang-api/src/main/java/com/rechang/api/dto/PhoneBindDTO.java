package com.rechang.api.dto;

import lombok.Data;

@Data
public class PhoneBindDTO {
    private String encryptedData;
    private String iv;
    private String phone; // for mock mode - direct phone number
}
