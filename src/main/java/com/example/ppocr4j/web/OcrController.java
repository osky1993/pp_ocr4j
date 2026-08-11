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
     * 模型档次列表及可用状态，供前端渲染选择器。
     */
    @GetMapping("/api/ocr/tiers")
    public Map<String, Object> tiers() {
        return Map.of(
                "defaultTier", engineManager.getDefaultTier(),
                "tiers", engineManager.listTiers());
    }

    /**
     * 上传图片识别：curl -F "file=@test_images/1.png" -F "tier=small" http://localhost:8080/api/ocr
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
     */
    @GetMapping("/api/ocr/demo")
    public OcrResponse demo(@RequestParam(value = "tier", required = false) String tier) {
        long start = System.currentTimeMillis();
        List<PPOcrV6Result> results = ocrService.recognizeFile("test_images/1.png", tier);
        return OcrResponse.of("test_images/1.png", resolvedTier(tier), results,
                System.currentTimeMillis() - start);
    }

    private String resolvedTier(String tier) {
        return (tier == null || tier.isBlank()) ? engineManager.getDefaultTier() : tier.toLowerCase();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }
}
