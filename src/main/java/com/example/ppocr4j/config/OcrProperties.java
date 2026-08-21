package com.example.ppocr4j.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 组件级配置（ocr.*），与 starter 的 mica.ai.ppocr.*（引擎调参）区分：
 * 这里管的是组件的资源保护、部署与安全行为。
 */
@ConfigurationProperties(prefix = "ocr")
public class OcrProperties {

    /** 模型根目录，子目录为 tiny/small/medium */
    private String modelRoot = "models/ppocr-v6";

    /** 解码后像素数上限（宽×高），防解码炸弹打爆堆外内存；默认 4000 万 ≈ 6300×6300 */
    private long maxPixels = 40_000_000L;

    /** 识别并发上限（信号量），0 = CPU 核数 */
    private int concurrency = 0;

    /** 单次识别超时（毫秒）；超时后请求返回 2002，任务线程自然跑完释放许可 */
    private long timeoutMs = 30_000L;

    /** 启动预热的模型档次（校验文件并各跑一次小图，消除首请求毛刺） */
    private List<String> warmupTiers = List.of("tiny");

    /** API Key 白名单；空 = 鉴权关闭（内网默认），非空则校验 /api/** 的 X-API-Key 头 */
    private List<String> apiKeys = List.of();

    /**
     * 推理加速器：cpu（默认，bit-exact）/ auto / coreml（macOS）/ cuda（NVIDIA，需 -Pgpu）。
     * 非 cpu 时使用本项目 AcceleratedPPOcrV6Engine（修复上游 provider 不生效缺陷）。
     */
    private String accelerator = "cpu";

    /** 异步任务配置 */
    private final Task task = new Task();

    public static class Task {
        /** 任务结果保留时长（分钟），过期清理 */
        private long ttlMinutes = 30;

        /** 异步任务结果保留时长（分钟）。 */
        public long getTtlMinutes() {
            return ttlMinutes;
        }

        /** 设置异步任务结果保留时长（分钟）。TTL 过小会导致前端来不及查询。 */
        public void setTtlMinutes(long ttlMinutes) {
            this.ttlMinutes = ttlMinutes;
        }
    }

    /** 实际生效的并发数（处理 0 = 核数的语义）。 */
    /**
     * 返回并生效值：
     * <ul>
     *   <li>配置为 0：按 JVM 可见 CPU 核数兜底</li>
     *   <li>> 0：固定并发闸门数</li>
     * </ul>
     * <p>该值直接驱动 {@link com.example.ppocr4j.service.OcrExecutor} 的 semaphore 与线程池大小。</p>
     */
    public int effectiveConcurrency() {
        return concurrency > 0 ? concurrency : Runtime.getRuntime().availableProcessors();
    }

    /** 获取模型根目录；子目录应是 tiny / small / medium。 */
    public String getModelRoot() {
        return modelRoot;
    }

    /** 设置模型根目录。建议先改配置文件，不建议运行期改动。 */
    public void setModelRoot(String modelRoot) {
        this.modelRoot = modelRoot;
    }

    /** 获取图片最大像素数上限（宽×高）。 */
    public long getMaxPixels() {
        return maxPixels;
    }

    /**
     * 设置图片最大像素数上限。
     *
     * <p>该阈值用于防止「解码炸弹」；过小会导致高分辨率图片被拒绝，过大则增加 OOM 风险。</p>
     */
    public void setMaxPixels(long maxPixels) {
        this.maxPixels = maxPixels;
    }

    /** 获取并发配置值（原始配置，0 表示交给 {@link #effectiveConcurrency()} 处理）。 */
    public int getConcurrency() {
        return concurrency;
    }

    /** 设置并发。建议与 CPU 核数和部署 QPS 目标一致设置。 */
    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    /** 获取单次同步识别超时（毫秒），超时返回 2002。 */
    public long getTimeoutMs() {
        return timeoutMs;
    }

    /** 设置单次同步识别超时（毫秒）。异步任务路径不受该值限制。 */
    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /** 获取预热档次列表（来自 yml，按顺序执行）。 */
    public List<String> getWarmupTiers() {
        return warmupTiers;
    }

    /** 设置启动预热档次；空列表表示不做预热。 */
    public void setWarmupTiers(List<String> warmupTiers) {
        this.warmupTiers = warmupTiers;
    }

    /** 获取 API Key 白名单（空列表表示关闭鉴权）。 */
    public List<String> getApiKeys() {
        return apiKeys;
    }

    /** 设置 API Key 白名单。 */
    public void setApiKeys(List<String> apiKeys) {
        this.apiKeys = apiKeys;
    }

    /** 获取推理加速器配置。 */
    public String getAccelerator() {
        return accelerator;
    }

    /** 设置推理加速器。 */
    public void setAccelerator(String accelerator) {
        this.accelerator = accelerator;
    }

    /** 获取异步任务 TTL 配置块。 */
    public Task getTask() {
        return task;
    }
}
