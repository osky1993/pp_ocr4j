package com.example.ppocr4j.config;

import net.dreamlu.mica.ai.ppocr.autoconfigure.PPOCRPropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PPOCRPropertiesCustomizer SPI 示例：在 application.yml 之外做旁路覆盖。
 * 通过环境变量 PPOCR_TIER=tiny|small|medium 切换模型档次，
 * 同样的思路也适用于从配置中心（如 Nacos）动态下发模型路径。
 */
@Configuration(proxyBeanMethods = false)
public class OcrTierCustomizer {

    /**
     * 通过环境变量覆盖 Starter 注入的默认模型路径。
     *
     * <p>注意：这是启动期一次性自定义，不会在运行中监听环境变量变化。</p>
     *
     * <p>示例：{@code PPOCR_TIER=small}</p>
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
