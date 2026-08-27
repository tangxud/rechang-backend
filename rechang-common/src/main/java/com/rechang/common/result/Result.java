package com.rechang.common.result;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {
    private int code;
    private String level;
    private String message;
    private T data;

    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getLevel().name(), ResultCode.SUCCESS.getMessage(), data);
    }

    public static <T> Result<T> success() {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getLevel().name(), ResultCode.SUCCESS.getMessage(), null);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, Severity.ERROR.name(), message, null);
    }

    public static <T> Result<T> error(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getLevel().name(), resultCode.getMessage(), null);
    }

    public static <T> Result<T> error(ResultCode resultCode, String message) {
        return new Result<>(resultCode.getCode(), resultCode.getLevel().name(), message, null);
    }

    public static <T> Result<T> of(ResultCode resultCode, String message) {
        return new Result<>(resultCode.getCode(), resultCode.getLevel().name(), message, null);
    }
}
