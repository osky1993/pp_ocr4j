package com.example.ppocr4j.health;

import com.example.ppocr4j.service.OcrEngineManager;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * OCR 就绪指示器：默认档模型文件在位才算就绪。
 * 纳入 readiness 探针组（见 application.yml），k8s/负载均衡以此决定是否放流量。
 * 默认档引擎由 Starter 在启动期创建，创建失败应用根本起不来，
 * 这里主要防运行期模型文件被误删/挂载丢失。
 */
@Component("ocrReadiness")
public class OcrReadinessIndicator implements HealthIndicator {

    private final OcrEngineManager engineManager;

    /**
     * 健康指示器构造函数。
     *
     * <p>持有引擎管理器，用于动态读取默认档次是否存在（模型未挂载时立即 down）。</p>
     */
    public OcrReadinessIndicator(OcrEngineManager engineManager) {
        this.engineManager = engineManager;
    }

    /**
     * readiness 探针逻辑：
     * <ul>
     *   <li>若默认档模型文件缺失：down</li>
     *   <li>若存在：up，并携带当前档次状态明细</li>
     *   <li>用于 K8s/LB 判断是否放量</li>
     * </ul>
     */
    @Override
    public Health health() {
        String defaultTier = engineManager.getDefaultTier();
        boolean modelsPresent = engineManager.isAvailable(defaultTier);
        Health.Builder builder = modelsPresent ? Health.up() : Health.down();
        return builder
                .withDetail("defaultTier", defaultTier)
                .withDetail("defaultTierModelsPresent", modelsPresent)
                .withDetail("tiers", engineManager.listTiers())
                .build();
    }
}
