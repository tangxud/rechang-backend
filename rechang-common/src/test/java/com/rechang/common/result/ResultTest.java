package com.rechang.common.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResultTest {

    @Test
    @DisplayName("success(data): code=200 level=INFO data 回传")
    void successWithData() {
        Result<String> r = Result.success("payload");
        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getLevel()).isEqualTo("INFO");
        assertThat(r.getMessage()).isEqualTo("success");
        assertThat(r.getData()).isEqualTo("payload");
    }

    @Test
    @DisplayName("error(code,msg): 固定 ERROR 级别且无数据")
    void errorWithRawCode() {
        Result<Void> r = Result.error(1001, "自定义错误");
        assertThat(r.getCode()).isEqualTo(1001);
        assertThat(r.getLevel()).isEqualTo("ERROR");
        assertThat(r.getMessage()).isEqualTo("自定义错误");
        assertThat(r.getData()).isNull();
    }

    @Test
    @DisplayName("error(ResultCode): 使用枚举默认文案与级别")
    void errorWithResultCode() {
        Result<Void> r = Result.error(ResultCode.PURCHASE_LIMIT_EXCEEDED);
        assertThat(r.getCode()).isEqualTo(1015);
        assertThat(r.getLevel()).isEqualTo("WARN");
        assertThat(r.getMessage()).isEqualTo(ResultCode.PURCHASE_LIMIT_EXCEEDED.getMessage());
    }

    @Test
    @DisplayName("error(ResultCode,msg): 覆盖文案保留级别")
    void errorWithResultCodeAndMessage() {
        Result<Void> r = Result.error(ResultCode.BAD_REQUEST, "站票数量必须大于0");
        assertThat(r.getCode()).isEqualTo(400);
        assertThat(r.getLevel()).isEqualTo("WARN");
        assertThat(r.getMessage()).isEqualTo("站票数量必须大于0");
    }
}
