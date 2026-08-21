package com.example.ppocr4j.exception;

import com.example.ppocr4j.web.ErrorCode;

/**
 * 组件业务异常：携带对外错误码，由 GlobalExceptionHandler 统一转成 ApiResult。
 */
public class OcrException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * 业务异常构造函数。
     *
     * <p>所有外部可观测错误都应明确附带 {@link ErrorCode}，这样 Controller 层可统一转换为
     * {@code ApiResult}；否则上游只能收到 500，业务系统无法做错误分支处理。</p>
     *
     * @param errorCode 对外错误码枚举，决定 code/httpStatus
     * @param message   人类可读错误说明
     */
    public OcrException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 业务异常构造函数（保留异常链）。
     *
     * <p>当底层库抛出原始异常时，用这个构造器保留 cause，便于日志中定位原始失败点。</p>
     *
     * @param errorCode 对外错误码
     * @param message 人类可读错误说明
     * @param cause 上游原始异常
     */
    public OcrException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 获取该异常对应的对外错误码。
     *
     * <p>建议只在错误分支和接口返回时读取，不建议作为复杂判断的业务主干条件。</p>
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
