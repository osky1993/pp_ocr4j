package com.example.ppocr4j.service;

import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按模型档次（tiny / small / medium）管理 {@link PPOcrV6Engine}：
 * 首次请求某档时懒加载并缓存，应用关闭时统一释放。
 * Starter 自动装配的默认引擎也注册进来复用，避免同档双实例。
 * 懒加载引擎的调参（阈值、批大小、线程数等）继承 application.yml
 * 生效后的基底配置，仅替换三个模型路径——保证三档行为一致。
 */
@Component
public class OcrEngineManager implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OcrEngineManager.class);

    public static final List<String> TIERS = List.of("tiny", "small", "medium");
    private static final Path MODEL_ROOT = Path.of("models", "ppocr-v6");

    private final Map<String, PPOcrV6Engine> engines = new ConcurrentHashMap<>();
    private final PPOcrV6Config baseConfig;
    private final String defaultTier;

    public OcrEngineManager(PPOcrV6Engine defaultEngine, PPOcrV6Config config) {
        this.baseConfig = config;
        this.defaultTier = TIERS.stream()
                .filter(t -> config.getDetModelPath().contains("/" + t + "/"))
                .findFirst()
                .orElse("tiny");
        // Starter 引擎的生命周期由 Spring 管理（destroy 时自动 close），
        // destroy() 里跳过它，只关闭本类懒加载创建的引擎
        engines.put(defaultTier, defaultEngine);
    }

    public String getDefaultTier() {
        return defaultTier;
    }

    /** 某档模型文件是否已就位（det.onnx + rec.onnx + dict.txt 齐全）。 */
    public boolean isAvailable(String tier) {
        Path dir = MODEL_ROOT.resolve(tier);
        return Files.isRegularFile(dir.resolve("det.onnx"))
                && Files.isRegularFile(dir.resolve("rec.onnx"))
                && Files.isRegularFile(dir.resolve("dict.txt"));
    }

    /** 各档次可用状态，供前端渲染选择器。 */
    public List<Map<String, Object>> listTiers() {
        return TIERS.stream()
                .<Map<String, Object>>map(t -> Map.of(
                        "tier", t,
                        "available", isAvailable(t),
                        "loaded", engines.containsKey(t),
                        "isDefault", t.equals(defaultTier)))
                .toList();
    }

    /**
     * 获取指定档次的引擎，未加载则创建（首次调用有模型加载耗时）。
     *
     * @param tier tiny / small / medium，null 或空串回落到默认档
     * @throws IllegalArgumentException 档次非法或模型文件缺失
     */
    public PPOcrV6Engine getEngine(String tier) {
        String t = (tier == null || tier.isBlank()) ? defaultTier : tier.toLowerCase();
        if (!TIERS.contains(t)) {
            throw new IllegalArgumentException("未知模型档次: " + tier + "，可选值: " + TIERS);
        }
        if (!isAvailable(t)) {
            throw new IllegalArgumentException("模型档次 " + t + " 的文件未下载，请参考 README 下载到 models/ppocr-v6/" + t + "/");
        }
        return engines.computeIfAbsent(t, key -> {
            Path dir = MODEL_ROOT.resolve(key);
            log.info("加载 {} 档 PP-OCRv6 模型: {}", key, dir);
            return new PPOcrV6Engine(PPOcrV6Config.builder()
                    // yml（含 PPOCRPropertiesCustomizer）生效后的调参作为公共基底
                    .detLimitSideLen(baseConfig.getDetLimitSideLen())
                    .detLimitType(baseConfig.getDetLimitType())
                    .detMaxSideLimit(baseConfig.getDetMaxSideLimit())
                    .detThresh(baseConfig.getDetThresh())
                    .detBoxThresh(baseConfig.getDetBoxThresh())
                    .detUnclipRatio(baseConfig.getDetUnclipRatio())
                    .recImageShape(baseConfig.getRecImageShape())
                    .recBatchSize(baseConfig.getRecBatchSize())
                    .preferAccelerator(baseConfig.isPreferAccelerator())
                    .intraOpNumThreads(baseConfig.getIntraOpNumThreads())
                    .interOpNumThreads(baseConfig.getInterOpNumThreads())
                    // 仅模型路径按档次替换
                    .detModelPath(dir.resolve("det.onnx").toString())
                    .recModelPath(dir.resolve("rec.onnx").toString())
                    .recCharDictPath(dir.resolve("dict.txt").toString())
                    .build());
        });
    }

    @Override
    public void destroy() {
        engines.forEach((tier, engine) -> {
            if (!tier.equals(defaultTier)) {
                log.info("关闭 {} 档引擎", tier);
                engine.close();
            }
        });
        engines.clear();
    }
}
