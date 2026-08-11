package com.example.ppocr4j.service;

import com.example.ppocr4j.exception.OcrException;
import com.example.ppocr4j.web.ErrorCode;
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
/**
 * OCR 识别服务层。
 *
 * <p>主要职责：
 * <ul>
 *   <li>把上传字节流或文件路径转成 OpenCV {@link Mat}。</li>
 *   <li>校验图片是否可解码/可读取。</li>
 *   <li>委托 {@link OcrEngineManager} 按 tier 执行识别。</li>
 *   <li>确保原生内存资源及时释放，避免图片未关闭导致的长期占用。</li>
 * </ul>
 * </p>
 */
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
        // 将 MultipartFile 传入的二进制字节流还原为 OpenCV 矩阵。
        // MatOfByte 只做临时封装，真正数据放在 Mat image 中。
        Mat image = Imgcodecs.imdecode(new MatOfByte(imageBytes), Imgcodecs.IMREAD_COLOR);
        if (image.empty()) {
            throw new OcrException(ErrorCode.IMAGE_DECODE_ERROR, "无法解码图片，请上传 png/jpg 等常见格式");
        }
        try {
            // 懒加载按档次引擎：首次请求可触发模型加载，后续复用同实例。
            return engineManager.getEngine(tier).run(image);
        } finally {
            // 释放 OpenCV 本地矩阵，避免 native heap 持续增长。
            image.release();
        }
    }

    /**
     * 识别本地磁盘上的图片文件。
     */
    public List<PPOcrV6Result> recognizeFile(String path, String tier) {
        // 直接从本地路径读取，适用于 demo 场景或服务端脚本触发识别。
        Mat image = Imgcodecs.imread(path);
        if (image.empty()) {
            throw new OcrException(ErrorCode.INVALID_PARAM, "图片不存在或无法读取: " + path);
        }
        try {
            // 同 recognize()，按 tier 选择器路由到默认或指定模型档次。
            return engineManager.getEngine(tier).run(image);
        } finally {
            // Mat 为 native 对象，必须手动释放。
            image.release();
        }
    }
}
