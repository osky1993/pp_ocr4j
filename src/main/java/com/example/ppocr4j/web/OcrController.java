package com.example.ppocr4j.web;

import com.example.ppocr4j.exception.OcrException;
import com.example.ppocr4j.service.OcrEngineManager;
import com.example.ppocr4j.service.OcrService;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
public class OcrController {

    private final OcrService ocrService;
    private final OcrEngineManager engineManager;
    private final ObjectProvider<BuildProperties> buildProperties;

    public OcrController(OcrService ocrService, OcrEngineManager engineManager,
                         ObjectProvider<BuildProperties> buildProperties) {
        this.ocrService = ocrService;
        this.engineManager = engineManager;
        this.buildProperties = buildProperties;
    }

    /**
     * 返回模型档次元数据，供前端下拉/按钮状态渲染。
     *
     * <p>返回结构：
     * <ul>
     *   <li>defaultTier：未传 tier 时服务端回退档次</li>
     *   <li>tiers：每个档次的可用性/加载状态/isDefault</li>
     * </ul></p>
     */
    @GetMapping("/api/ocr/tiers")
    public ApiResult<Map<String, Object>> tiers() {
        return ApiResult.ok(Map.of(
                "defaultTier", engineManager.getDefaultTier(),
                "tiers", engineManager.listTiers()));
    }

    /**
     * 上传图片识别接口。
     *
     * <p>处理流程：
     * <ul>
     *   <li>校验文件是否为空</li>
     *   <li>记录开始时间，调用服务层进行识别</li>
     *   <li>按实际生效的 tier 组装统一响应</li>
     * </ul></p>
     *
     * <p>示例：<code>curl -F "file=@test_images/1.png" -F "tier=small" -F "rotate=90" http://localhost:8080/api/ocr</code></p>
     *
     * @param file       上传文件，支持 multipart/form-data
     * @param tier       模型档次（tiny/small/medium，可选；为空按默认）
     * @param rotate     识别前顺时针旋转角度（0/90/180/270，默认 0）
     * @param autoRotate true 时四方向自动试探选优（约 4 倍 tiny 耗时），忽略 rotate
     */
    @PostMapping("/api/ocr")
    public ApiResult<OcrResponse> ocr(@RequestParam("file") MultipartFile file,
                                      @RequestParam(value = "tier", required = false) String tier,
                                      @RequestParam(value = "rotate", defaultValue = "0") int rotate,
                                      @RequestParam(value = "autoRotate", defaultValue = "false") boolean autoRotate)
            throws IOException {
        if (file.isEmpty()) {
            throw new OcrException(ErrorCode.INVALID_PARAM, "上传文件为空");
        }
        long start = System.currentTimeMillis();
        List<PPOcrV6Result> results = ocrService.recognize(file.getBytes(), tier, rotate, autoRotate);
        return ApiResult.ok(OcrResponse.of(file.getOriginalFilename(), resolvedTier(tier), results,
                System.currentTimeMillis() - start));
    }

    /**
     * base64 识别接口：便于从 MQ/内部 RPC 携带图片字节的调用方（无需构造 multipart）。
     *
     * <p>请求体：{@code {"image": "<base64>", "tier": "small", "rotate": 0, "autoRotate": false}}，
     * image 支持裸 base64 或 data URL（data:image/png;base64,...）。</p>
     */
    @PostMapping("/api/ocr/base64")
    public ApiResult<OcrResponse> ocrBase64(@RequestBody Base64Request req) {
        byte[] bytes = req.toBytes();
        long start = System.currentTimeMillis();
        List<PPOcrV6Result> results = ocrService.recognize(bytes, req.tier(),
                req.rotateOrDefault(), req.autoRotateOrDefault());
        return ApiResult.ok(OcrResponse.of("base64", resolvedTier(req.tier()), results,
                System.currentTimeMillis() - start));
    }

    /**
     * 组件信息接口：版本、构建时间、依赖库版本、各档模型状态。灰度确认与排障入口。
     */
    @GetMapping("/api/ocr/info")
    public ApiResult<Map<String, Object>> info() {
        BuildProperties build = buildProperties.getIfAvailable();
        return ApiResult.ok(Map.of(
                "name", build != null ? build.getName() : "pp-ocr4j",
                "version", build != null ? build.getVersion() : "unknown",
                "buildTime", build != null && build.getTime() != null ? build.getTime().toString() : "unknown",
                "micaPpocrVersion", build != null ? String.valueOf(build.get("mica-ppocr")) : "unknown",
                "accelerator", engineManager.getAccelerator().name().toLowerCase(),
                "defaultTier", engineManager.getDefaultTier(),
                "tiers", engineManager.listTiers()));
    }

    /**
     * 统一将 tier 参数转为可落库/落日志的标准值。
     *
     * <p>说明：空值回退到默认档，非空值统一转小写，保证前端和后端一致性。</p>
     *
     * @param tier 外部输入 tier（可能为空）
     * @return 实际生效档次
     */
    private String resolvedTier(String tier) {
        return (tier == null || tier.isBlank()) ? engineManager.getDefaultTier() : tier.toLowerCase();
    }
}
