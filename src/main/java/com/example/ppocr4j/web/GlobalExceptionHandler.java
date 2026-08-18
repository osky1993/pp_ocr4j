package com.example.ppocr4j.web;

import com.example.ppocr4j.exception.OcrException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常 → 统一 ApiResult 转换。调用方永远拿到 {code, message, traceId} 结构，
 * 不会看到 Spring 默认的 500 白板页。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常兜底：将 OcrException 转为统一结构并保留原始 HTTP 状态码语义。
     */
    @ExceptionHandler(OcrException.class)
    public ResponseEntity<ApiResult<Void>> ocrException(OcrException e) {
        ErrorCode ec = e.getErrorCode();
        // 业务可预期错误记 warn 即可，5000 才是需要关注的内部错误
        if (ec == ErrorCode.INTERNAL_ERROR) {
            log.error("OCR 内部错误: {}", e.getMessage(), e);
        } else {
            log.warn("OCR 业务错误 [{}]: {}", ec.code(), e.getMessage());
        }
        return ResponseEntity.status(ec.httpStatus()).body(ApiResult.error(ec, e.getMessage()));
    }

    /**
     * 兼容仍在抛 IllegalArgumentException 的旧路径，一律按参数错误处理。
     *
     * <p>例如早期自定义实现或第三方库在参数非法时仍抛 Java 标准异常时，统一映射为 1001。</p>
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResult<Void>> illegalArgument(IllegalArgumentException e) {
        log.warn("参数错误: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.INVALID_PARAM.httpStatus())
                .body(ApiResult.error(ErrorCode.INVALID_PARAM, e.getMessage()));
    }

    /**
     * 上传体积超上限统一处理，避免 500 白页与模糊错误信息。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResult<Void>> uploadTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(ErrorCode.IMAGE_TOO_LARGE.httpStatus())
                .body(ApiResult.error(ErrorCode.IMAGE_TOO_LARGE, "上传体积超过限制"));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ApiResult<Void>> missingParam(Exception e) {
        return ResponseEntity.status(ErrorCode.INVALID_PARAM.httpStatus())
                .body(ApiResult.error(ErrorCode.INVALID_PARAM, "缺少必要参数: " + e.getMessage()));
    }

    /**
     * 非 multipart 请求打到 multipart 接口（如空 POST /api/ocr）时返回参数错误，便于前端修复请求格式。
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResult<Void>> notMultipart(MultipartException e) {
        return ResponseEntity.status(ErrorCode.INVALID_PARAM.httpStatus())
                .body(ApiResult.error(ErrorCode.INVALID_PARAM, "请求必须为 multipart/form-data 且携带 file 字段"));
    }

    /**
     * 静态资源 404 保持原语义，不包装成 5000。
     * 这样前端可直接区分“资源不存在”与“业务错误”。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResult<Void>> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(404)
                .body(ApiResult.error(ErrorCode.INVALID_PARAM, "资源不存在: /" + e.getResourcePath()));
    }

    /** 全局兜底：未分支异常统一返回 5000，避免把堆栈外泄给调用方。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> unexpected(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ApiResult.error(ErrorCode.INTERNAL_ERROR, "内部错误，请携带 traceId 联系组件维护方"));
    }
}
