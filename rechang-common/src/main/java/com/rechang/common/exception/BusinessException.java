package com.rechang.common.exception;

import com.rechang.common.result.ResultCode;
import com.rechang.common.result.Severity;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final int code;
    private final Severity level;

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
        this.level = resultCode.getLevel();
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
        this.level = resultCode.getLevel();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.level = Severity.ERROR;
    }
}
