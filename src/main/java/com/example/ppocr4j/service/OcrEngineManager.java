package com.example.ppocr4j.service;

import com.example.ppocr4j.config.Accelerator;
import com.example.ppocr4j.config.OcrProperties;
import com.example.ppocr4j.engine.AcceleratedPPOcrV6Engine;
import com.example.ppocr4j.exception.OcrException;
import com.example.ppocr4j.web.ErrorCode;
import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import org.opencv.core.Mat;
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
 * 按模型档次（tiny / small / medium）管理 {@link OcrEngine}：
 * 首次请求某档时懒加载并缓存，应用关闭时统一释放。
 *
 * <p>引擎实现按 ocr.accelerator 二选一：
 * <ul>
 *   <li>cpu（默认）：库原版 {@link PPOcrV6Engine}，保持 bit-exact；
 *       Starter 自动装配的默认引擎注册进来复用，避免同档双实例。</li>
 *   <li>auto/coreml/cuda：本项目 {@link AcceleratedPPOcrV6Engine}（修复了上游
 *       provider 不生效的缺陷）；此时 Starter 引擎闲置不用（tiny 档内存代价可忽略）。</li>
 * </ul>
 *
 * <p>懒加载引擎的调参（阈值、批大小、线程数等）继承 application.yml
 * 生效后的基底配置，仅替换三个模型路径——保证三档行为一致。</p>
 */
@Component
public class OcrEngineManager implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OcrEngineManager.class);

    /** 支持的模型档次，按轻量到高精度顺序排序。 */
    public static final List<String> TIERS = List.of("tiny", "small", "medium");

    /** 模型根目录（来自 ocr.model-root 配置），子目录应分别包含 det.onnx / rec.onnx / dict.txt。 */
    private final Path modelRoot;

    /** 缓存已经创建的引擎实例。key 为模型档次。 */
    private final Map<String, OcrEngine> engines = new ConcurrentHashMap<>();

    /** 从 starter 注入的默认配置快照，用于构造其他档次引擎的基底参数。 */
    private final PPOcrV6Config baseConfig;

    /** 当请求未传 tier 或传入空值时的默认 fallback 档。 */
    private final String defaultTier;

    /** 生效的加速器（启动期解析一次并落日志）。 */
    private final Accelerator accelerator;

    /**
     * 组装引擎管理器。
     *
     * <p>职责分解：
     * <ul>
     *   <li>读取 starter 的默认模型路径，推断默认档次。</li>
     *   <li>读取全局加速策略（CPU / AUTO / COREML / CUDA）。</li>
     *   <li>如果启动为 CPU，默认档位引擎复用 Starter 实例并缓存；其余模式按档位延迟创建。</li>
     *   <li>失败路径下，非法 tier 与模型缺失会在调用 `getEngine` 时快速返回统一错误码。</li>
     * </ul>
     * </p>
     */
    public OcrEngineManager(PPOcrV6Engine defaultEngine, PPOcrV6Config config, OcrProperties props) {
        this.baseConfig = config;
        this.modelRoot = Path.of(props.getModelRoot());
        this.accelerator = Accelerator.parse(props.getAccelerator());
        // 通过默认 det 模型路径判断当前 YAML 中配置的是哪个档次（tiny/small/medium 之一）。
        this.defaultTier = TIERS.stream()
                .filter(t -> config.getDetModelPath().contains("/" + t + "/"))
                .findFirst()
                .orElse("tiny");
        if (accelerator == Accelerator.CPU) {
            // Starter 提供的引擎由 Spring 生命周期管理（adapter 的 close 为 no-op），
            // 先注册进去避免重复创建默认档实例。
            engines.put(defaultTier, new LibraryEngineAdapter(defaultEngine, true));
        } else {
            log.info("加速模式 {} 已开启：各档引擎由 AcceleratedPPOcrV6Engine 按需创建（解析为 {}），" +
                    "Starter 默认引擎闲置；注意加速后不再保证 bit-exact", accelerator, accelerator.resolve());
        }
    }

    public String getDefaultTier() {
        return defaultTier;
    }

    /** 当前实例启动后生效的加速策略（CPU / AUTO / COREML / CUDA）。 */
    public Accelerator getAccelerator() {
        return accelerator;
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
        Path dir = modelRoot.resolve(tier);
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
     *   <li>非法 tier 立即抛出 1001，模型文件缺失抛出 1004。</li>
     *   <li>首次调用的档次将会懒加载并写入缓存，后续复用。</li>
     *   <li>新建引擎只替换 det/rec/dict 模型路径，其余参数继承 baseConfig。</li>
     * </ul></p>
     *
     * @param tier tiny / small / medium，null 或空串回落到默认档
     * @throws OcrException 档次非法或模型文件缺失
     */
    public OcrEngine getEngine(String tier) {
        String t = (tier == null || tier.isBlank()) ? defaultTier : tier.toLowerCase();
        if (!TIERS.contains(t)) {
            throw new OcrException(ErrorCode.INVALID_PARAM, "未知模型档次: " + tier + "，可选值: " + TIERS);
        }
        if (!isAvailable(t)) {
            throw new OcrException(ErrorCode.TIER_UNAVAILABLE,
                    "模型档次 " + t + " 的文件未下载，请参考 README 下载到 models/ppocr-v6/" + t + "/");
        }
        return engines.computeIfAbsent(t, key -> {
            Path dir = modelRoot.resolve(key);
            log.info("加载 {} 档 PP-OCRv6 模型: {}（accelerator={}）", key, dir, accelerator);
            PPOcrV6Config config = PPOcrV6Config.builder()
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
                    // 1.1.1+ 文档方向分类：doc_ori 模型全档共享，随基底配置传递
                    .useDocOrientationClassify(baseConfig.isUseDocOrientationClassify())
                    .docOrientationModelPath(baseConfig.getDocOrientationModelPath())
                    .docOrientationThresh(baseConfig.getDocOrientationThresh())
                    // 仅模型路径按档次替换
                    .detModelPath(dir.resolve("det.onnx").toString())
                    .recModelPath(dir.resolve("rec.onnx").toString())
                    .recCharDictPath(dir.resolve("dict.txt").toString())
                    .build();
            if (accelerator == Accelerator.CPU) {
                return new LibraryEngineAdapter(new PPOcrV6Engine(config), false);
            }
            return new AcceleratedPPOcrV6Engine(config, accelerator);
        });
    }

    /**
     * 应用关闭时释放所有非托管引擎，清空运行时缓存。
     */
    @Override
    public void destroy() {
        // adapter 对 Starter 引擎的 close 为 no-op（由 Spring 管理），其余实例真实释放
        engines.forEach((tier, engine) -> {
            log.info("关闭 {} 档引擎", tier);
            engine.close();
        });
        engines.clear();
    }

    /**
     * 库原版引擎适配器。managedExternally=true 表示生命周期由 Spring 管理（Starter 装配的
     * 默认引擎，Bean 销毁时 Spring 会调用其 close），此时本类的 close 为 no-op 防止双重释放。
     */
    private record LibraryEngineAdapter(PPOcrV6Engine delegate, boolean managedExternally) implements OcrEngine {

        /**
         * 委托执行。保持与本项目自研加速引擎一致的 run 语义，不在这里做状态变更。
         * managedExternally=true 时不在本类调用 close，避免与 Spring 生命周期重复关闭。
         */
        @Override
        public List<PPOcrV6Result> run(Mat imgBgr) {
            // 1.1.x 起 Mat 入参重命名为 runMat（Mat 生命周期由调用方管理，与本项目约定一致）
            return delegate.runMat(imgBgr);
        }

        /** 关闭该适配器持有的引擎（若托管给 Spring 则跳过）。 */
        @Override
        public void close() {
            if (!managedExternally) {
                delegate.close();
            }
        }
    }
}
