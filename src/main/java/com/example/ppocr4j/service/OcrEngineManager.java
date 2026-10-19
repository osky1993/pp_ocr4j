package com.example.ppocr4j.service;

import com.example.ppocr4j.config.Accelerator;
import com.example.ppocr4j.config.OcrProperties;
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
 * <p>引擎统一使用库原版 {@link PPOcrV6Engine}（1.2.2+ 的 preferAccelerator 已真正注册 EP）。
 * ocr.accelerator 启动期经 {@link Accelerator#resolve()} 按本机可用 provider 判定，
 * 再映射为各档引擎的 preferAccelerator：
 * <ul>
 *   <li>解析为 cpu（默认，或请求的加速器本机不可用）：preferAccelerator=false，保持 bit-exact；</li>
 *   <li>解析为 coreml/cuda：preferAccelerator=true，上游按 CoreML > CUDA 自动选择，
 *       resolve 已保证选中的就是请求的那个。</li>
 * </ul>
 * Starter 自动装配的默认引擎仅在其 preferAccelerator 与上述映射一致时注册进来复用（避免同档双实例），
 * 否则闲置不用（tiny 档内存代价可忽略）。
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

    /** 配置的加速器（ocr.accelerator 原值）。 */
    private final Accelerator accelerator;

    /** 映射给各档引擎的 preferAccelerator（启动期按本机可用 provider 解析一次并落日志）。 */
    private final boolean preferAccelerator;

    /**
     * 组装引擎管理器。
     *
     * <p>职责分解：
     * <ul>
     *   <li>读取 starter 的默认模型路径，推断默认档次。</li>
     *   <li>读取全局加速策略（CPU / AUTO / COREML / CUDA）。</li>
     *   <li>Starter 实例的 preferAccelerator 与映射结果一致时复用为默认档引擎；其余按档位延迟创建。</li>
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
        Accelerator resolved = accelerator.resolve();
        this.preferAccelerator = resolved != Accelerator.CPU;
        if (accelerator != Accelerator.CPU && accelerator != Accelerator.AUTO && resolved == Accelerator.CPU) {
            log.warn("ocr.accelerator={} 在本机不可用（或上游自动选择会选中其他加速器），回落 CPU", accelerator);
        } else if (preferAccelerator) {
            log.info("加速模式 {} 已开启（解析为 {}）：各档引擎 preferAccelerator=true；注意加速后不再保证 bit-exact",
                    accelerator, resolved);
        }
        if (config.isPreferAccelerator() == preferAccelerator) {
            // Starter 提供的引擎由 Spring 生命周期管理（adapter 的 close 为 no-op），
            // 先注册进去避免重复创建默认档实例。
            engines.put(defaultTier, new LibraryEngineAdapter(defaultEngine, true));
        } else if (config.isPreferAccelerator()) {
            log.warn("mica.ppocr.prefer-accelerator=true 与 ocr.accelerator={} 不一致，以 ocr.accelerator 为准，" +
                    "Starter 默认引擎闲置", accelerator);
        } else {
            log.info("Starter 默认引擎（CPU）闲置，各档引擎按加速配置懒加载");
        }
    }

    public String getDefaultTier() {
        return defaultTier;
    }

    /** 配置的加速策略（CPU / AUTO / COREML / CUDA）。 */
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
            // yml（含 PPOCRPropertiesCustomizer）生效后的调参作为公共基底，toBuilder 全量继承
            // （含 doc_ori 共享模型与 1.2.x 新增的 arena / memory pattern / execMode），
            // 仅加速开关（由 ocr.accelerator 决定）与模型路径按档次替换
            PPOcrV6Config config = baseConfig.toBuilder()
                    .preferAccelerator(preferAccelerator)
                    .detModelPath(dir.resolve("det.onnx").toString())
                    .recModelPath(dir.resolve("rec.onnx").toString())
                    .recCharDictPath(dir.resolve("dict.txt").toString())
                    .build();
            return new LibraryEngineAdapter(new PPOcrV6Engine(config), false);
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
         * 委托执行，不在这里做状态变更。
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
