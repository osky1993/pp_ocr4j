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

    /** AUTO 按运行环境实际可用的 provider 解析为具体加速器。 */
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
