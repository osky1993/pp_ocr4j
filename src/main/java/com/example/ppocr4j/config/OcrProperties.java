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

        public long getTtlMinutes() {
            return ttlMinutes;
        }

        public void setTtlMinutes(long ttlMinutes) {
            this.ttlMinutes = ttlMinutes;
        }
    }

    /** 实际生效的并发数（处理 0 = 核数的语义）。 */
    public int effectiveConcurrency() {
        return concurrency > 0 ? concurrency : Runtime.getRuntime().availableProcessors();
    }

    public String getModelRoot() {
        return modelRoot;
    }

    public void setModelRoot(String modelRoot) {
        this.modelRoot = modelRoot;
    }

    public long getMaxPixels() {
        return maxPixels;
    }

    public void setMaxPixels(long maxPixels) {
        this.maxPixels = maxPixels;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public List<String> getWarmupTiers() {
        return warmupTiers;
    }

    public void setWarmupTiers(List<String> warmupTiers) {
        this.warmupTiers = warmupTiers;
    }

    public List<String> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<String> apiKeys) {
        this.apiKeys = apiKeys;
    }

    public String getAccelerator() {
        return accelerator;
    }

    public void setAccelerator(String accelerator) {
        this.accelerator = accelerator;
    }

    public Task getTask() {
        return task;
    }
}
