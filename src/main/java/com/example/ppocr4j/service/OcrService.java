package com.example.ppocr4j.service;

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * OCR 服务：通过 {@link OcrEngineManager} 按档次取引擎执行识别。
 */
@Service
public class OcrService {

    private final OcrEngineManager engineManager;

    public OcrService(OcrEngineManager engineManager) {
        this.engineManager = engineManager;
    }

    /**
     * 识别图片字节流（如 HTTP 上传的文件）。
     *
     * @param tier 模型档次 tiny/small/medium，null 使用默认档
     * @throws IllegalArgumentException 字节流不是可解码的图片，或档次非法/模型缺失
     */
    public List<PPOcrV6Result> recognize(byte[] imageBytes, String tier) {
        Mat image = Imgcodecs.imdecode(new MatOfByte(imageBytes), Imgcodecs.IMREAD_COLOR);
        if (image.empty()) {
            throw new IllegalArgumentException("无法解码图片，请上传 png/jpg 等常见格式");
        }
        try {
            return engineManager.getEngine(tier).run(image);
        } finally {
            image.release();
        }
    }

    /**
     * 识别本地磁盘上的图片文件。
     */
    public List<PPOcrV6Result> recognizeFile(String path, String tier) {
        Mat image = Imgcodecs.imread(path);
        if (image.empty()) {
            throw new IllegalArgumentException("图片不存在或无法读取: " + path);
        }
        try {
            return engineManager.getEngine(tier).run(image);
        } finally {
            image.release();
        }
    }
}
