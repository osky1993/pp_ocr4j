package com.example.ppocr4j.web;

import org.springframework.http.HttpStatus;

/**
 * 组件级错误码表（对外契约，调用方按 code 分支处理，勿随意改动已发布的取值）。
 */
public enum ErrorCode {

    /** 成功 */
    OK(0, HttpStatus.OK),
    /** 参数错误（缺参、取值非法、任务不存在等） */
    INVALID_PARAM(1001, HttpStatus.BAD_REQUEST),
    /** 图片解码失败（非图片字节流或格式不支持） */
    IMAGE_DECODE_ERROR(1002, HttpStatus.BAD_REQUEST),
    /** 图片过大（超出 ocr.max-pixels 像素上限或上传体积限制） */
    IMAGE_TOO_LARGE(1003, HttpStatus.BAD_REQUEST),
    /** 模型档次不可用（模型文件未就位） */
    TIER_UNAVAILABLE(1004, HttpStatus.BAD_REQUEST),
    /** 并发超限，请稍后重试 */
    RATE_LIMITED(2001, HttpStatus.TOO_MANY_REQUESTS),
    /** 识别超时 */
    TIMEOUT(2002, HttpStatus.GATEWAY_TIMEOUT),
    /** 未授权（X-API-Key 缺失或无效） */
    UNAUTHORIZED(4010, HttpStatus.UNAUTHORIZED),
    /** 引擎或内部错误 */
    INTERNAL_ERROR(5000, HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final HttpStatus httpStatus;

    /**
     * 错误码定义构造。
     *
     * @param code      对外固定 code，需兼容历史客户端
     * @param httpStatus HTTP 语义状态码
     */
    ErrorCode(int code, HttpStatus httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /** 对应对外返回码。 */
    public int code() {
        return code;
    }

    /** 对应 HTTP 状态码，用于统一响应头与网关策略。 */
    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
