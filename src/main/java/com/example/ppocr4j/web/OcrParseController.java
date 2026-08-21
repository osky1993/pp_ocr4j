package com.example.ppocr4j.web;

import com.example.ppocr4j.exception.OcrException;
import com.example.ppocr4j.service.OcrEngineManager;
import com.example.ppocr4j.service.OcrParseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 证件/票据字段级结构化提取接口。
 *
 * <p>支持类型：vehicle-license（行驶证）/ id-card（身份证）/ bank-card（银行卡）/
 * driver-license（驾驶证）/ business-license（营业执照）/ invoice（增值税发票）。
 * tier/rotate/autoRotate 参数语义与 /api/ocr 一致；服务端默认已开启 doc_ori 自动转正。</p>
 */
@RestController
public class OcrParseController {

    private final OcrParseService parseService;
    private final OcrEngineManager engineManager;

    /**
     * 注入结构化识别服务与引擎管理器。
     *
     * <p>结构化流程与普通 OCR 共用参数语义，主要区别在于结果组装为字段级 Map。</p>
     */
    public OcrParseController(OcrParseService parseService, OcrEngineManager engineManager) {
        this.parseService = parseService;
        this.engineManager = engineManager;
    }

    /** 支持的证件类型列表（供调用方发现与前端渲染）。 */
    @GetMapping("/api/ocr/parse/types")
    public ApiResult<Map<String, Object>> types() {
        return ApiResult.ok(Map.of("types", parseService.supportedTypes()));
    }

    /**
     * 结构化提取（multipart）：
     * curl -F "file=@xxx.jpg" -F "tier=small" http://localhost:8080/api/ocr/parse/vehicle-license
     */
    @PostMapping("/api/ocr/parse/{docType}")
    public ApiResult<ParseResponse> parse(@PathVariable String docType,
                                          @RequestParam("file") MultipartFile file,
                                          @RequestParam(value = "tier", required = false) String tier,
                                          @RequestParam(value = "rotate", defaultValue = "0") int rotate,
                                          @RequestParam(value = "autoRotate", defaultValue = "false") boolean autoRotate)
            throws IOException {
        if (file.isEmpty()) {
            throw new OcrException(ErrorCode.INVALID_PARAM, "上传文件为空");
        }
        long start = System.currentTimeMillis();
        OcrParseService.Outcome outcome = parseService.parse(file.getBytes(), docType, tier, rotate, autoRotate);
        return ApiResult.ok(ParseResponse.of(file.getOriginalFilename(), resolvedTier(tier), outcome,
                System.currentTimeMillis() - start));
    }

    /**
     * 结构化提取（base64 JSON）：请求体同 /api/ocr/base64。
     */
    @PostMapping("/api/ocr/parse/{docType}/base64")
    public ApiResult<ParseResponse> parseBase64(@PathVariable String docType, @RequestBody Base64Request req) {
        long start = System.currentTimeMillis();
        OcrParseService.Outcome outcome = parseService.parse(req.toBytes(), docType, req.tier(),
                req.rotateOrDefault(), req.autoRotateOrDefault());
        return ApiResult.ok(ParseResponse.of("base64", resolvedTier(req.tier()), outcome,
                System.currentTimeMillis() - start));
    }

    /** 统一归一化 tier（空值回退默认档，非空转小写）供响应回填。 */
    private String resolvedTier(String tier) {
        return (tier == null || tier.isBlank()) ? engineManager.getDefaultTier() : tier.toLowerCase();
    }
}
