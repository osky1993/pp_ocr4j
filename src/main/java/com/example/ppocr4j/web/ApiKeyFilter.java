package com.example.ppocr4j.web;

import com.example.ppocr4j.config.OcrProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 轻量鉴权与调用方标识（内网可信定位，目的不是防攻击而是知道「谁在用、用了多少」）：
 *
 * <ul>
 *   <li>ocr.api-keys 非空时，/api/** 请求必须携带白名单内的 X-API-Key，否则 4010；
 *       为空（默认）则鉴权关闭。静态资源与 /actuator 永不拦截。</li>
 *   <li>X-Caller 头（调用方系统标识）写入 MDC，随日志 pattern 输出，供容量归因。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)   // 在 TraceIdFilter 之后，保证 401 响应也带 traceId
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    public static final String CALLER = "caller";
    public static final String KEY_HEADER = "X-API-Key";
    public static final String CALLER_HEADER = "X-Caller";

    private final OcrProperties props;
    private final ObjectMapper objectMapper;

    /**
     * 注入配置与序列化器。ObjectMapper 用于返回 401 时直接写 JSON，避免默认错误页。
     */
    public ApiKeyFilter(OcrProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * 鉴权过滤主流程：
     * <ul>
     *   <li>无配置时放行，不做鉴权</li>
     *   <li>配置非空时，只拦 `/api/**`</li>
     *   <li>缺失/不匹配 Key 则返回 4010，不进入 controller</li>
     *   <li>记录 caller 到 MDC，便于日志聚合按调用方归因</li>
     * </ul>
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String caller = request.getHeader(CALLER_HEADER);
        if (caller != null && !caller.isBlank()) {
            MDC.put(CALLER, caller);
        }
        try {
            if (!props.getApiKeys().isEmpty() && request.getRequestURI().startsWith("/api/")) {
                String key = request.getHeader(KEY_HEADER);
                if (key == null || !props.getApiKeys().contains(key)) {
                    response.setStatus(ErrorCode.UNAUTHORIZED.httpStatus().value());
                    response.setContentType("application/json;charset=UTF-8");
                    objectMapper.writeValue(response.getWriter(),
                            ApiResult.error(ErrorCode.UNAUTHORIZED, "缺少或无效的 " + KEY_HEADER + " 请求头"));
                    return;
                }
            }
            boolean isApi = request.getRequestURI().startsWith("/api/");
            long start = System.currentTimeMillis();
            try {
                chain.doFilter(request, response);
            } finally {
                // /api 访问日志：pattern 中的 [caller] 标识调用方，用于容量归因
                if (isApi) {
                    log.info("{} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(),
                            response.getStatus(), System.currentTimeMillis() - start);
                }
            }
        } finally {
            MDC.remove(CALLER);
        }
    }
}
