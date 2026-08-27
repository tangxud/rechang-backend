package com.rechang.common.exception;

import com.rechang.common.result.Result;
import com.rechang.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GlobalExceptionHandler 直调测试（不起 Spring 容器）。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private final HttpServletRequest request = mock(HttpServletRequest.class);

    /** 供构造 MethodArgumentNotValidException 的占位方法 */
    @SuppressWarnings("unused")
    private void sampleEndpoint(String name) {
    }

    @Test
    @DisplayName("BusinessException → 透传 code/level/message，HTTP 200 信封")
    void businessException() {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/orders");
        Result<Void> result = handler.handleBusinessException(
                new BusinessException(ResultCode.PURCHASE_LIMIT_EXCEEDED), request);
        assertThat(result.getCode()).isEqualTo(1015);
        assertThat(result.getLevel()).isEqualTo("WARN");
        assertThat(result.getData()).isNull();
    }

    @Test
    @DisplayName("MethodArgumentNotValidException → 取字段错误文案")
    void validationException_withFieldError() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "dto");
        bindingResult.addError(new FieldError("dto", "name", "观演人姓名不能为空"));
        MethodParameter parameter = new MethodParameter(
                getClass().getDeclaredMethod("sampleEndpoint", String.class), -1);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        Result<Void> result = handler.handleValidationException(ex);
        assertThat(result.getCode()).isEqualTo(ResultCode.BAD_REQUEST.getCode());
        assertThat(result.getMessage()).isEqualTo("观演人姓名不能为空");
    }

    @Test
    @DisplayName("MethodArgumentNotValidException 无字段错误 → 默认文案")
    void validationException_withoutFieldError() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "dto");
        MethodParameter parameter = new MethodParameter(
                getClass().getDeclaredMethod("sampleEndpoint", String.class), -1);

        Result<Void> result = handler.handleValidationException(new MethodArgumentNotValidException(parameter, bindingResult));
        assertThat(result.getMessage()).isEqualTo("参数校验失败");
    }

    @Test
    @DisplayName("BindException → 取字段错误文案")
    void bindException() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "query");
        bindingResult.addError(new FieldError("query", "page", "分页参数非法"));
        BindException ex = new BindException(bindingResult);

        Result<Void> result = handler.handleBindException(ex);
        assertThat(result.getMessage()).isEqualTo("分页参数非法");
    }

    @Test
    @DisplayName("兜底 Exception → INTERNAL_ERROR；且 HTTP 状态为 500")
    void unexpectedException() {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/home");
        RuntimeException boom = new RuntimeException("boom");

        Result<Void> result = handler.handleException(boom, request);
        assertThat(result.getCode()).isEqualTo(ResultCode.INTERNAL_ERROR.getCode());
        assertThat(result.getLevel()).isEqualTo("ERROR");

        try {
            ResponseStatus anno = GlobalExceptionHandler.class
                    .getMethod("handleException", Exception.class, HttpServletRequest.class)
                    .getAnnotation(ResponseStatus.class);
            assertThat(anno.value()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }
}
