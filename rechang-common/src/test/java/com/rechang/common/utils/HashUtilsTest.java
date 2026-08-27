package com.rechang.common.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HashUtils 纯函数测试：身份证校验（GB11643 加权校验码）、各类脱敏规则。
 */
class HashUtilsTest {

    // 经 GB11643 校验码算法验证的合法号码
    private static final String VALID_ID_1 = "110101199003070011";
    private static final String VALID_ID_2 = "330102199001010012";
    private static final String VALID_ID_WITH_X = "11010519491231002X";
    // 校验码正确但出生日期在未来 → 非法
    private static final String FUTURE_BIRTH_ID = "110101209901010017";

    @Test
    @DisplayName("isValidIdCard: 合法号码通过（含末位 X）")
    void isValidIdCard_acceptsValidNumbers() {
        assertThat(HashUtils.isValidIdCard(VALID_ID_1)).isTrue();
        assertThat(HashUtils.isValidIdCard(VALID_ID_2)).isTrue();
        assertThat(HashUtils.isValidIdCard(VALID_ID_WITH_X.toLowerCase())).isTrue();
    }

    @Test
    @DisplayName("isValidIdCard: 格式非法直接拒绝")
    void isValidIdCard_rejectsBadFormat() {
        assertThat(HashUtils.isValidIdCard(null)).isFalse();
        assertThat(HashUtils.isValidIdCard("")).isFalse();
        assertThat(HashUtils.isValidIdCard("123")).isFalse();
        assertThat(HashUtils.isValidIdCard("11010119900307001a")).isFalse(); // 含字母
        assertThat(HashUtils.isValidIdCard("1101011990030700")).isFalse();   // 17位缺校验码
    }

    @Test
    @DisplayName("isValidIdCard: 出生日期非法或未来拒绝")
    void isValidIdCard_rejectsInvalidBirthDate() {
        assertThat(HashUtils.isValidIdCard(FUTURE_BIRTH_ID)).isFalse();
        // 13月不存在（校验码不参与，日期解析即失败）
        assertThat(HashUtils.isValidIdCard("110101199013070011")).isFalse();
    }

    @Test
    @DisplayName("isValidIdCard: 校验码错误拒绝")
    void isValidIdCard_rejectsWrongCheckDigit() {
        String tampered = VALID_ID_1.substring(0, 17)
                + (VALID_ID_1.charAt(17) == '9' ? '8' : '9');
        assertThat(tampered).isNotEqualTo(VALID_ID_1);
        assertThat(HashUtils.isValidIdCard(tampered)).isFalse();
    }

    @Test
    @DisplayName("sha256: 确定性输出 64 位十六进制")
    void sha256_isDeterministicHex64() {
        String a = HashUtils.sha256("330102199001011234");
        String b = HashUtils.sha256("330102199001011234");
        assertThat(a).isEqualTo(b).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(a).isNotEqualTo(HashUtils.sha256("different"));
    }

    @Test
    @DisplayName("maskIdCard: 保留前4后4")
    void maskIdCard_keepsHeadAndTail() {
        assertThat(HashUtils.maskIdCard(VALID_ID_1))
                .isEqualTo("1101" + "*".repeat(10) + "0011");
    }

    @Test
    @DisplayName("maskIdCard: 不足 10 位返回 ***")
    void maskIdCard_shortInput() {
        assertThat(HashUtils.maskIdCard(null)).isEqualTo("***");
        assertThat(HashUtils.maskIdCard("123456789")).isEqualTo("***");
    }

    @Test
    @DisplayName("maskPhone: 保留前3后4")
    void maskPhone() {
        assertThat(HashUtils.maskPhone("13888888888")).isEqualTo("138****8888");
    }

    @Test
    @DisplayName("maskPhone: 少于 7 位原样返回")
    void maskPhone_shortInput() {
        assertThat(HashUtils.maskPhone(null)).isNull();
        assertThat(HashUtils.maskPhone("123456")).isEqualTo("123456");
    }

    @Test
    @DisplayName("maskName: 按长度分段脱敏")
    void maskName() {
        assertThat(HashUtils.maskName(null)).isNull();
        assertThat(HashUtils.maskName("张")).isEqualTo("张");
        assertThat(HashUtils.maskName("张三")).isEqualTo("张*");
        assertThat(HashUtils.maskName("张三明")).isEqualTo("张*明");
        assertThat(HashUtils.maskName("欧阳三明子")).isEqualTo("欧***子");
    }

    @Test
    @DisplayName("身份证与 hash 一一对应：同号同 hash 异号异 hash")
    void sha256_idCardMapping() {
        assertThat(HashUtils.sha256(VALID_ID_1)).isNotEqualTo(HashUtils.sha256(VALID_ID_2));
    }
}
