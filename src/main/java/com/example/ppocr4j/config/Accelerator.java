package com.example.ppocr4j.config;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtProvider;

/**
 * 推理加速器选项（ocr.accelerator）。
 *
 * <p>注意：mica-ppocr 1.0.1 的 prefer-accelerator 存在缺陷（provider 只记日志不生效），
 * 因此加速由本项目的 AcceleratedPPOcrV6Engine 实现。开启任何加速后不再保证与
 * Python 参考实现 bit-exact。</p>
 */
public enum Accelerator {

    /** 纯 CPU（默认）：使用库原版引擎，保持 bit-exact */
    CPU,
    /** 自动探测：CoreML(macOS) > CUDA(NVIDIA) > CPU */
    AUTO,
    /** Apple CoreML（macOS）；CUDA 依赖需 -Pgpu profile */
    COREML,
    /** NVIDIA CUDA（Linux/Windows，需 -Pgpu profile 切换 onnxruntime_gpu 依赖） */
    CUDA;

    /**
     * 解析配置里的加速器字符串。
     *
     * <p>规则：
     * <ul>
     *   <li>空值或空白 -> fallback 到 CPU</li>
     *   <li>区分大小写，允许 cpu/auto/coreml/cuda</li>
     *   <li>非法值直接失败（启动即可感知），避免服务以“静默默认”启动后行为不确定</li>
     * </ul>
     * </p>
     *
     * @param value ocr.accelerator 配置值
     * @return 标准化后的枚举
     */
    public static Accelerator parse(String value) {
        if (value == null || value.isBlank()) {
            return CPU;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ocr.accelerator 取值非法: " + value + "，可选 cpu/auto/coreml/cuda");
        }
    }

    /**
     * 将 AUTO 映射为当前机器实际可用加速器。
     *
     * <p>优先级为 CoreML > CUDA > CPU：同一台机器如果有多个后端可用，优先走性能更佳/开箱体验更好的
     * CoreML，再次判断 CUDA，最后回退 CPU。</p>
     *
     * <p>这个方法不执行实际 ONNX session 创建，真正应用 EP 在 engine 初始化阶段。
     * 因此可在调用前安全预判可用性，用于日志和启动诊断。</p>
     */
    public Accelerator resolve() {
        if (this != AUTO) {
            return this;
        }
        var available = OrtEnvironment.getAvailableProviders();
        if (available.contains(OrtProvider.CORE_ML)) {
            return COREML;
        }
        if (available.contains(OrtProvider.CUDA)) {
            return CUDA;
        }
        return CPU;
    }
}
