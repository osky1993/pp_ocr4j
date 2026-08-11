package com.example.ppocr4j.web;

import org.slf4j.MDC;

/**
 * 统一响应包装：{@code {code, message, traceId, data}}。
 *
 * <p>code=0 表示成功；非 0 见 {@link ErrorCode} 错误码表。
 * traceId 与请求头 X-Request-Id 一致（未传时由 {@link TraceIdFilter} 生成），
 * 用于跨系统串联排障日志。
 */
public record ApiResult<T>(int code, String message, String traceId, T data) {

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(ErrorCode.OK.code(), "success", currentTraceId(), data);
    }

    public static ApiResult<Void> error(ErrorCode errorCode, String message) {
        return new ApiResult<>(errorCode.code(), message, currentTraceId(), null);
    }

    static String currentTraceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
