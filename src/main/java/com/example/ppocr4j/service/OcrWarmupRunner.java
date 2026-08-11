package com.example.ppocr4j.service;

import com.example.ppocr4j.config.OcrProperties;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动自检与预热：按 ocr.warmup-tiers 校验模型文件并各跑一次小图，
 * 消除首请求的模型加载毛刺（medium 首次加载约 +7s）。
 * 配置了预热档但模型文件缺失时 fail-fast，避免带病上线。
 */
@Component
public class OcrWarmupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OcrWarmupRunner.class);

    private final OcrEngineManager engineManager;
    private final OcrProperties props;

    public OcrWarmupRunner(OcrEngineManager engineManager, OcrProperties props) {
        this.engineManager = engineManager;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String tier : props.getWarmupTiers()) {
            if (!engineManager.isAvailable(tier)) {
                throw new IllegalStateException("预热档次 " + tier + " 的模型文件缺失，" +
                        "请下载到 " + props.getModelRoot() + "/" + tier + "/ 或从 ocr.warmup-tiers 移除该档");
            }
            long start = System.currentTimeMillis();
            // 64x64 纯白小图：完整走一遍 det+rec 流水线（无文字，结果为空），只为触发加载与 JIT
            Mat blank = new Mat(64, 64, CvType.CV_8UC3, new Scalar(255, 255, 255));
            try {
                engineManager.getEngine(tier).run(blank);
            } finally {
                blank.release();
            }
            log.info("预热完成: tier={}, 耗时 {} ms", tier, System.currentTimeMillis() - start);
        }
    }
}
