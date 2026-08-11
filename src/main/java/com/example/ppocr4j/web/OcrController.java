package com.example.ppocr4j.web;

import com.example.ppocr4j.service.OcrEngineManager;
import com.example.ppocr4j.service.OcrService;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
public class OcrController {

    private final OcrService ocrService;
    private final OcrEngineManager engineManager;

    public OcrController(OcrService ocrService, OcrEngineManager engineManager) {
        this.ocrService = ocrService;
        this.engineManager = engineManager;
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
    public Map<String, Object> tiers() {
        return Map.of(
                "defaultTier", engineManager.getDefaultTier(),
                "tiers", engineManager.listTiers());
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
     * <p>示例：<code>curl -F "file=@test_images/1.png" -F "tier=small" http://localhost:8080/api/ocr</code></p>
     *
     * @param file 上传文件，支持 multipart/form-data
     * @param tier 模型档次（tiny/small/medium，可选；为空按默认）
     */
    @PostMapping("/api/ocr")
    public OcrResponse ocr(@RequestParam("file") MultipartFile file,
                           @RequestParam(value = "tier", required = false) String tier) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        long start = System.currentTimeMillis();
        List<PPOcrV6Result> results = ocrService.recognize(file.getBytes(), tier);
        return OcrResponse.of(file.getOriginalFilename(), resolvedTier(tier), results,
                System.currentTimeMillis() - start);
    }

    /**
     * 演示接口：识别仓库自带的行驶证测试图 test_images/1.png。
     *
     * <p>用于快速验证服务可用性，不依赖上传流程，但仍走同样 OCR 引擎路径。</p>
     *
     * @param tier 选择档次（可选）
     */
    @GetMapping("/api/ocr/demo")
    public OcrResponse demo(@RequestParam(value = "tier", required = false) String tier) {
        long start = System.currentTimeMillis();
        List<PPOcrV6Result> results = ocrService.recognizeFile("test_images/1.png", tier);
        return OcrResponse.of("test_images/1.png", resolvedTier(tier), results,
                System.currentTimeMillis() - start);
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

    /**
     * 将业务侧可预期的参数错误映射为 400 语义，避免返回 500。
     *
     * @param e 参数/状态异常
     * @return 错误提示字符串
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }
}
