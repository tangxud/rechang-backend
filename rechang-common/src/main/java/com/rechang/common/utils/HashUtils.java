package com.rechang.common.utils;

import cn.hutool.crypto.digest.DigestUtil;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class HashUtils {

    private static final int[] ID_CARD_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] ID_CARD_CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    public static String sha256(String input) {
        return DigestUtil.sha256Hex(input);
    }

    public static boolean isValidIdCard(String idCard) {
        if (idCard == null || !idCard.matches("^\\d{17}[\\dXx]$")) {
            return false;
        }
        String birthStr = idCard.substring(6, 14);
        try {
            LocalDate birth = LocalDate.parse(birthStr, DateTimeFormatter.ofPattern("yyyyMMdd"));
            if (birth.isAfter(LocalDate.now())) {
                return false;
            }
        } catch (DateTimeParseException e) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (idCard.charAt(i) - '0') * ID_CARD_WEIGHTS[i];
        }
        char expectedCheck = ID_CARD_CHECK_CODES[sum % 11];
        char actualCheck = Character.toUpperCase(idCard.charAt(17));
        return expectedCheck == actualCheck;
    }

    public static String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 10) {
            return "***";
        }
        return idCard.substring(0, 4) + "**********" + idCard.substring(idCard.length() - 4);
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    public static String maskName(String name) {
        if (name == null || name.length() <= 1) {
            return name;
        }
        if (name.length() == 2) {
            return name.charAt(0) + "*";
        }
        return name.charAt(0) + "*".repeat(name.length() - 2) + name.charAt(name.length() - 1);
    }
}
