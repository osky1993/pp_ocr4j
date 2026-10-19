package com.example.ppocr4j.config;

import ai.onnxruntime.OrtProvider;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * resolve 的结果会映射成上游 preferAccelerator：非 CPU 时上游按 CoreML > CUDA 自动选择，
 * 所以这里锁定「显式值只在上游必然选中它时才开启」。
 */
class AcceleratorTest {

    /** macOS 上 ORT CPU 包的典型枚举结果。 */
    private static final Set<OrtProvider> MAC = EnumSet.of(OrtProvider.CPU, OrtProvider.CORE_ML);
    /** Linux + onnxruntime_gpu（-Pgpu）的典型枚举结果。 */
    private static final Set<OrtProvider> LINUX_GPU = EnumSet.of(OrtProvider.CPU, OrtProvider.CUDA);
    private static final Set<OrtProvider> CPU_ONLY = EnumSet.of(OrtProvider.CPU);

    @Test
    void cpuNeverAccelerates() {
        assertThat(Accelerator.CPU.resolve(MAC)).isEqualTo(Accelerator.CPU);
        assertThat(Accelerator.CPU.resolve(LINUX_GPU)).isEqualTo(Accelerator.CPU);
    }

    @Test
    void autoFollowsUpstreamPriority() {
        assertThat(Accelerator.AUTO.resolve(MAC)).isEqualTo(Accelerator.COREML);
        assertThat(Accelerator.AUTO.resolve(LINUX_GPU)).isEqualTo(Accelerator.CUDA);
        assertThat(Accelerator.AUTO.resolve(CPU_ONLY)).isEqualTo(Accelerator.CPU);
    }

    @Test
    void explicitValueFallsBackToCpuWhenUnavailable() {
        assertThat(Accelerator.COREML.resolve(MAC)).isEqualTo(Accelerator.COREML);
        assertThat(Accelerator.COREML.resolve(LINUX_GPU)).isEqualTo(Accelerator.CPU);
        assertThat(Accelerator.CUDA.resolve(LINUX_GPU)).isEqualTo(Accelerator.CUDA);
        // 共享配置 cuda 落到 Mac：不能开 preferAccelerator，否则上游会选成 CoreML
        assertThat(Accelerator.CUDA.resolve(MAC)).isEqualTo(Accelerator.CPU);
        assertThat(Accelerator.CUDA.resolve(EnumSet.of(OrtProvider.CORE_ML, OrtProvider.CUDA)))
                .isEqualTo(Accelerator.CPU);
    }

    @Test
    void parseIsCaseInsensitiveAndRejectsUnknown() {
        assertThat(Accelerator.parse(null)).isEqualTo(Accelerator.CPU);
        assertThat(Accelerator.parse(" CoreML ")).isEqualTo(Accelerator.COREML);
        assertThatThrownBy(() -> Accelerator.parse("tensorrt")).isInstanceOf(IllegalStateException.class);
    }
}
