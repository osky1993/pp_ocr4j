package com.example.ppocr4j.service;

import com.example.ppocr4j.exception.OcrException;
import com.example.ppocr4j.web.ErrorCode;
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

    /** 支持的模型档次，按轻量到高精度顺序排序。 */
    public static final List<String> TIERS = List.of("tiny", "small", "medium");

    /** 模型根目录，子目录应分别包含 det.onnx / rec.onnx / dict.txt。 */
    private static final Path MODEL_ROOT = Path.of("models", "ppocr-v6");

    /** 缓存已经创建的引擎实例。key 为模型档次。 */
    private final Map<String, PPOcrV6Engine> engines = new ConcurrentHashMap<>();

    /** 从 starter 注入的默认配置快照，用于构造其他档次引擎的基底参数。 */
    private final PPOcrV6Config baseConfig;

    /** 当请求未传 tier 或传入空值时的默认 fallback 档。 */
    private final String defaultTier;

    public OcrEngineManager(PPOcrV6Engine defaultEngine, PPOcrV6Config config) {
        this.baseConfig = config;
        // 通过默认 det 模型路径判断当前 YAML 中配置的是哪个档次（tiny/small/medium 之一）。
        this.defaultTier = TIERS.stream()
                .filter(t -> config.getDetModelPath().contains("/" + t + "/"))
                .findFirst()
                .orElse("tiny");
        // Starter 提供的引擎由 Spring 生命周期管理，先注册进去避免重复创建默认档实例。
        engines.put(defaultTier, defaultEngine);
    }

    public String getDefaultTier() {
        return defaultTier;
    }

    /**
     * 判定某一档模型文件是否齐全。
     *
     * <p>完整性条件是：det.onnx、rec.onnx、dict.txt 都为普通文件。</p>
     *
     * @param tier 模型档次
     * @return 完整时返回 true
     */
    public boolean isAvailable(String tier) {
        Path dir = MODEL_ROOT.resolve(tier);
        return Files.isRegularFile(dir.resolve("det.onnx"))
                && Files.isRegularFile(dir.resolve("rec.onnx"))
                && Files.isRegularFile(dir.resolve("dict.txt"));
    }

    /**
     * 返回三档的运行时状态，用于前端渲染选择器。
     *
     * <p>返回字段说明：
     * <ul>
     *   <li>tier：档次名</li>
     *   <li>available：是否具备完整模型文件</li>
     *   <li>loaded：实例是否已被加载并缓存</li>
     *   <li>isDefault：是否为默认 fallback 档次</li>
     * </ul></p>
     */
    public List<Map<String, Object>> listTiers() {
        return TIERS.stream()
                .<Map<String, Object>>map(t -> Map.of(
                        "tier", t,
                        "available", isAvailable(t), // 前端依赖该值禁用未下载档次
                        "loaded", engines.containsKey(t), // 请求一次后会转为 true
                        "isDefault", t.equals(defaultTier)))
                .toList();
    }

    /**
     * 获取指定档次的引擎实例。
     *
     * <p>行为说明：
     * <ul>
     *   <li>tier 为空时回退到 defaultTier。</li>
     *   <li>非法 tier 立即抛出 {@link IllegalArgumentException}。</li>
     *   <li>如果模型文件缺失，明确提示下载路径。</li>
     *   <li>首次调用的档次将会懒加载并写入缓存，后续复用。</li>
     *   <li>新建引擎只替换 det/rec/dict 模型路径，其余参数继承 baseConfig。</li>
     * </ul></p>
     *
     * @param tier tiny / small / medium，null 或空串回落到默认档
     * @throws IllegalArgumentException 档次非法或模型文件缺失
     */
    public PPOcrV6Engine getEngine(String tier) {
        String t = (tier == null || tier.isBlank()) ? defaultTier : tier.toLowerCase();
        if (!TIERS.contains(t)) {
            throw new OcrException(ErrorCode.INVALID_PARAM, "未知模型档次: " + tier + "，可选值: " + TIERS);
        }
        if (!isAvailable(t)) {
            throw new OcrException(ErrorCode.TIER_UNAVAILABLE,
                    "模型档次 " + t + " 的文件未下载，请参考 README 下载到 models/ppocr-v6/" + t + "/");
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
                // 仅关闭本类创建的懒加载实例，starter 注入实例由 Spring 管理生命周期。
                engine.close();
            }
        });
        // 清空缓存，避免重复 close / 挂起引用。
        engines.clear();
    }
}
