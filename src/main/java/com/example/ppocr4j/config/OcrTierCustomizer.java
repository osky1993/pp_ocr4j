package com.example.ppocr4j.config;

import net.dreamlu.mica.ai.ppocr.autoconfigure.PPOCRPropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OcrTierCustomizer {

    /**
     * 通过环境变量覆盖 Starter 注入的默认模型路径。
     *
     * <p>注意：这是启动期一次性自定义，不会在运行中监听环境变量变化。</p>
     *
     * <p>示例：{@code PPOCR_TIER=small}</p>
     */
    /**
     * 覆盖 Starter 注入的 det/rec 模型路径，支持部署时只改环境变量完成档次切换。
     *
     * <p>流程：
     * <ul>
     *   <li>启动期读取 PPOCR_TIER</li>
     *   <li>若命中 tiny/small/medium，按固定目录覆盖 three paths</li>
     *   <li>若为空，不做任何变更，沿用 application.yml</li>
     * </ul></p>
     *
     * <p>注意：该 bean 在上下文初始化阶段执行一次。热切换请走动态配置中心并重启实例。</p>
     */
    @Bean
    PPOCRPropertiesCustomizer tierEnvCustomizer() {
        return builder -> {
            String tier = System.getenv("PPOCR_TIER");
            // 启动期读取一次环境变量；值仅在应用生命周期内固定一次，不支持运行时热切换。
            if (tier != null && !tier.isBlank()) {
                builder.detModelPath("models/ppocr-v6/" + tier + "/det.onnx")
                        .recModelPath("models/ppocr-v6/" + tier + "/rec.onnx")
                        .recCharDictPath("models/ppocr-v6/" + tier + "/dict.txt");
                // 因为本类不校验文件完整性，默认依赖 OcrEngineManager::isAvailable 在首次调用时兜底。
            }
        };
    }
}
