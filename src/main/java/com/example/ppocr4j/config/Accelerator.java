package com.example.ppocr4j.config;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtProvider;

import java.util.EnumSet;
import java.util.Set;

/**
 * 推理加速器选项（ocr.accelerator）。
 *
 * <p>真正注册 execution provider 的是 mica-ppocr 1.2.2+ 官方引擎（{@code OrtProviders.apply()}，
 * gitee #IKDEZI 修复了此前「只记日志不生效」的缺陷）。上游只有 {@code preferAccelerator} 一个布尔开关，
 * 开启后按 CoreML > CUDA > CPU 自动选择；本枚举在其之上补回「显式指定」语义：
 * {@link #resolve()} 先按当前环境判定实际能用哪个，再由调用方映射成 preferAccelerator。
 * 开启任何加速后不再保证与 Python 参考实现 bit-exact。</p>
 */
public enum Accelerator {

    /** 纯 CPU（默认）：保持 bit-exact */
    CPU,
    /** 自动探测：CoreML(macOS) > CUDA(NVIDIA) > CPU，与上游 preferAccelerator=true 一致 */
    AUTO,
    /** Apple CoreML（macOS）；不可用时回落 CPU */
    COREML,
    /** NVIDIA CUDA（Linux/Windows，需 -Pgpu profile 切换 onnxruntime_gpu 依赖）；不可用时回落 CPU */
    CUDA;

    /**
     * 解析配置里的加速器字符串。
     *
     * <p>规则：
     * <ul>
     *   <li>空值或空白 -> fallback 到 CPU</li>
     *   <li>不区分大小写，允许 cpu/auto/coreml/cuda</li>
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

    /** 按当前 ONNX Runtime 可枚举的 provider 解析，见 {@link #resolve(Set)}。 */
    public Accelerator resolve() {
        Set<OrtProvider> available;
        try {
            available = OrtEnvironment.getAvailableProviders();
        } catch (RuntimeException e) {
            // 与上游 OrtProviders.resolve() 一致：枚举失败按 CPU 处理
            available = EnumSet.noneOf(OrtProvider.class);
        }
        return resolve(available);
    }

    /**
     * 解析为本机实际会生效的加速器（CPU / COREML / CUDA，不会返回 AUTO）。
     *
     * <p>上游 preferAccelerator=true 时固定按 CoreML > CUDA 取第一个可用的，因此显式值只有在
     * 「上游自动选择的结果恰好就是它」时才能开启加速，否则回落 CPU——避免一份共享配置
     * {@code cuda} 落到 Mac 上被上游自动选成 CoreML（慢且精度下降，见 USAGE.md 第四节）。</p>
     *
     * @param available 当前可用的 provider
     * @return CPU 表示应关闭 preferAccelerator；其余值表示开启后上游必然选中该 provider
     */
    public Accelerator resolve(Set<OrtProvider> available) {
        boolean coreml = available.contains(OrtProvider.CORE_ML);
        boolean cuda = available.contains(OrtProvider.CUDA);
        return switch (this) {
            case CPU -> CPU;
            case AUTO -> coreml ? COREML : (cuda ? CUDA : CPU);
            case COREML -> coreml ? COREML : CPU;
            case CUDA -> cuda && !coreml ? CUDA : CPU;
        };
    }
}
