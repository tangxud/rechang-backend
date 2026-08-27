package com.rechang.common.exception;

import com.rechang.common.result.ResultCode;
import com.rechang.common.result.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessExceptionTest {

    @Test
    @DisplayName("BusinessException(ResultCode): 透传码/文案/级别")
    void fromResultCode() {
        BusinessException e = new BusinessException(ResultCode.SEAT_LOCKED);
        assertThat(e.getCode()).isEqualTo(1013);
        assertThat(e.getLevel()).isEqualTo(Severity.WARN);
        assertThat(e.getMessage()).isEqualTo("座位已被锁定");
    }

    @Test
    @DisplayName("BusinessException(ResultCode, msg): 自定义文案保留级别")
    void fromResultCodeWithMessage() {
        BusinessException e = new BusinessException(ResultCode.BAD_REQUEST, "观演人不存在: 9");
        assertThat(e.getCode()).isEqualTo(400);
        assertThat(e.getLevel()).isEqualTo(Severity.WARN);
        assertThat(e.getMessage()).isEqualTo("观演人不存在: 9");
    }

    @Test
    @DisplayName("BusinessException(int, msg): 强制 ERROR 级别")
    void rawCodeForcesErrorLevel() {
        BusinessException e = new BusinessException(50001, "下游服务不可用");
        assertThat(e.getCode()).isEqualTo(50001);
        assertThat(e.getLevel()).isEqualTo(Severity.ERROR);
    }
}
