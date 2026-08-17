package com.example.ppocr4j.web;

import com.example.ppocr4j.service.OcrEngineManager;
import com.example.ppocr4j.service.OcrService;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 演示接口（仅 dev profile）：识别仓库自带的行驶证测试图。
 * 本地默认 profile 为 dev（见 application.yml），生产部署显式指定 profile 后自动关闭。
 */
@RestController
@Profile("dev")
public class DemoController {

    private final OcrService ocrService;
    private final OcrEngineManager engineManager;

    public DemoController(OcrService ocrService, OcrEngineManager engineManager) {
        this.ocrService = ocrService;
        this.engineManager = engineManager;
    }

    @GetMapping("/api/ocr/demo")
    public ApiResult<OcrResponse> demo(@RequestParam(value = "tier", required = false) String tier) {
        long start = System.currentTimeMillis();
        List<PPOcrV6Result> results = ocrService.recognizeFile("test_images/1.png", tier);
        String resolved = (tier == null || tier.isBlank()) ? engineManager.getDefaultTier() : tier.toLowerCase();
        return ApiResult.ok(OcrResponse.of("test_images/1.png", resolved, results,
                System.currentTimeMillis() - start));
    }

    /** 返回自带测试图原始字节，供调试台/联调方同源获取样例图片。 */
    @GetMapping(value = "/api/ocr/demo-image", produces = org.springframework.http.MediaType.IMAGE_PNG_VALUE)
    public byte[] demoImage() throws java.io.IOException {
        return java.nio.file.Files.readAllBytes(java.nio.file.Path.of("test_images/1.png"));
    }
}
