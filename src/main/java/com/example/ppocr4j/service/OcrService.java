package com.example.ppocr4j.service;

import com.example.ppocr4j.config.OcrProperties;
import com.example.ppocr4j.exception.OcrException;
import com.example.ppocr4j.web.ErrorCode;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * OCR 识别服务层。
 *
 * <p>主要职责：
 * <ul>
 *   <li>把上传字节流或文件路径转成 OpenCV {@link Mat}，校验可解码性与像素上限。</li>
 *   <li>委托 {@link OcrEngineManager} 按 tier 执行识别。</li>
 *   <li>识别经 {@link OcrExecutor} 并发闸门执行，与 Tomcat 请求线程隔离。</li>
 *   <li>确保原生内存资源及时释放，避免图片未关闭导致的长期占用。</li>
 * </ul>
 *
 * <p>内存安全约定：解码、识别、释放整体在工作线程闭包内完成——超时场景下请求线程
 * 已返回而工作线程可能仍在使用 Mat，绝不能在请求线程侧释放。</p>
 */
@Service
public class OcrService {

    private final OcrEngineManager engineManager;
    private final OcrExecutor executor;
    private final OcrProperties props;

    public OcrService(OcrEngineManager engineManager, OcrExecutor executor, OcrProperties props) {
        this.engineManager = engineManager;
        this.executor = executor;
        this.props = props;
    }

    /**
     * 识别图片字节流（如 HTTP 上传的文件）。
     *
     * @param tier 模型档次 tiny/small/medium，null 使用默认档
     * @throws OcrException 图片非法(1002)/过大(1003)、档次非法(1001)/缺失(1004)、
     *                      并发超限(2001)、超时(2002)
     */
    public List<PPOcrV6Result> recognize(byte[] imageBytes, String tier) {
        return executor.execute(() -> doRecognize(decodeBytes(imageBytes), tier));
    }

    /**
     * 识别本地磁盘上的图片文件。
     */
    public List<PPOcrV6Result> recognizeFile(String path, String tier) {
        return executor.execute(() -> {
            Mat image = Imgcodecs.imread(path);
            if (image.empty()) {
                throw new OcrException(ErrorCode.INVALID_PARAM, "图片不存在或无法读取: " + path);
            }
            return doRecognize(image, tier);
        });
    }

    /** 在工作线程内完成校验、识别与释放。 */
    private List<PPOcrV6Result> doRecognize(Mat image, String tier) {
        try {
            checkPixels(image);
            return engineManager.getEngine(tier).run(image);
        } finally {
            image.release();
        }
    }

    private Mat decodeBytes(byte[] imageBytes) {
        // MatOfByte 只做临时封装，真正数据放在返回的 Mat 中
        Mat image = Imgcodecs.imdecode(new MatOfByte(imageBytes), Imgcodecs.IMREAD_COLOR);
        if (image.empty()) {
            throw new OcrException(ErrorCode.IMAGE_DECODE_ERROR, "无法解码图片，请上传 png/jpg 等常见格式");
        }
        return image;
    }

    private void checkPixels(Mat image) {
        long pixels = (long) image.rows() * image.cols();
        if (pixels > props.getMaxPixels()) {
            throw new OcrException(ErrorCode.IMAGE_TOO_LARGE,
                    "图片像素数 " + pixels + " 超过上限 " + props.getMaxPixels() + "（" +
                            image.cols() + "x" + image.rows() + "），请压缩后重试");
        }
    }
}
