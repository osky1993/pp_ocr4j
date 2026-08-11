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

    @Bean
    PPOCRPropertiesCustomizer tierEnvCustomizer() {
        return builder -> {
            String tier = System.getenv("PPOCR_TIER");
            if (tier != null && !tier.isBlank()) {
                builder.detModelPath("models/ppocr-v6/" + tier + "/det.onnx")
                        .recModelPath("models/ppocr-v6/" + tier + "/rec.onnx")
                        .recCharDictPath("models/ppocr-v6/" + tier + "/dict.txt");
            }
        };
    }
}
