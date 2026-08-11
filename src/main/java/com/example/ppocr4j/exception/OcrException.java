package com.example.ppocr4j.exception;

import com.example.ppocr4j.web.ErrorCode;

/**
 * 组件业务异常：携带对外错误码，由 GlobalExceptionHandler 统一转成 ApiResult。
 */
public class OcrException extends RuntimeException {

    private final ErrorCode errorCode;

    public OcrException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public OcrException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
