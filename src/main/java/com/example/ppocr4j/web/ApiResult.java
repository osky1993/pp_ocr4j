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

    /**
     * 标准成功响应构造：固定返回 code=0 与 success，traceId 自动从 MDC 读取。
     */
    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(ErrorCode.OK.code(), "success", currentTraceId(), data);
    }

    /**
     * 标准错误响应构造：code/message 来源 ErrorCode，data 固定为 null。
     */
    public static ApiResult<Void> error(ErrorCode errorCode, String message) {
        return new ApiResult<>(errorCode.code(), message, currentTraceId(), null);
    }

    /** 优先读取日志上下文里的 traceId，空时返回 null 由上层 JSON 正常输出。 */
    static String currentTraceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
