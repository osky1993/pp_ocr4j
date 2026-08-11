package com.example.ppocr4j.service;

import com.example.ppocr4j.config.OcrProperties;
import com.example.ppocr4j.exception.OcrException;
import com.example.ppocr4j.web.ErrorCode;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * OCR 识别服务层。
 *
 * <p>主要职责：
 * <ul>
 *   <li>把上传字节流或文件路径转成 OpenCV {@link Mat}，校验可解码性与像素上限。</li>
 *   <li>rotate：按调用方指定角度（90/180/270，顺时针）先转正再识别——
 *       mica-ppocr 无方向分类器（cls 模型），横拍/倒置图必须转正。</li>
 *   <li>autoRotate：用 tiny 档对四个方向各跑一次，按文本长度加权平均置信度选优。</li>
 *   <li>识别经 {@link OcrExecutor} 并发闸门执行，与 Tomcat 请求线程隔离。</li>
 * </ul>
 *
 * <p>内存安全约定：解码、旋转、识别、释放整体在工作线程闭包内完成——超时场景下
 * 请求线程已返回而工作线程可能仍在使用 Mat，绝不能在请求线程侧释放。</p>
 */
@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);
    private static final int[] AUTO_ROTATIONS = {0, 90, 180, 270};

    private final OcrEngineManager engineManager;
    private final OcrExecutor executor;
    private final OcrProperties props;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    public OcrService(OcrEngineManager engineManager, OcrExecutor executor, OcrProperties props,
                      io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.engineManager = engineManager;
        this.executor = executor;
        this.props = props;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 指标包装：ocr.recognize 计时器（tier/outcome 标签）+ ocr.rejected 拒绝计数器。
     * outcome: success / rejected(2001) / timeout(2002) / error（其余异常）。
     */
    private List<PPOcrV6Result> metered(String tier, java.util.function.Supplier<List<PPOcrV6Result>> action) {
        // tier 标签必须收敛到有限集合，防止非法输入造成 Prometheus 标签基数爆炸
        String normalized = (tier == null || tier.isBlank()) ? engineManager.getDefaultTier() : tier.toLowerCase();
        String tierTag = OcrEngineManager.TIERS.contains(normalized) ? normalized : "invalid";
        long start = System.nanoTime();
        String outcome = "success";
        try {
            return action.get();
        } catch (OcrException e) {
            outcome = switch (e.getErrorCode()) {
                case RATE_LIMITED -> "rejected";
                case TIMEOUT -> "timeout";
                default -> "error";
            };
            if (!"error".equals(outcome)) {
                meterRegistry.counter("ocr.rejected", "reason", outcome).increment();
            }
            throw e;
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            meterRegistry.timer("ocr.recognize", "tier", tierTag, "outcome", outcome)
                    .record(java.time.Duration.ofNanos(System.nanoTime() - start));
        }
    }

    /**
     * 识别图片字节流（如 HTTP 上传的文件或 base64 解码结果）。
     *
     * @param tier       模型档次 tiny/small/medium，null 使用默认档
     * @param rotate     识别前顺时针旋转角度，仅支持 0/90/180/270
     * @param autoRotate true 时忽略 rotate，四方向自动试探选优（约 4 倍 tiny 耗时）
     * @throws OcrException 图片非法(1002)/过大(1003)、参数或档次非法(1001)/缺失(1004)、
     *                      并发超限(2001)、超时(2002)
     */
    public List<PPOcrV6Result> recognize(byte[] imageBytes, String tier, int rotate, boolean autoRotate) {
        validateRotate(rotate);
        return metered(tier, () -> executor.execute(() -> {
            Mat image = decodeBytes(imageBytes);
            try {
                checkPixels(image);
                return recognizeOriented(image, tier, rotate, autoRotate);
            } finally {
                image.release();
            }
        }));
    }

    /** 异步识别的结果载体：实际生效档次、识别结果与执行耗时。 */
    public record TimedResults(String tier, List<PPOcrV6Result> results, long costMs) {}

    /**
     * 异步识别（供任务接口）：并发闸门满时本方法直接抛 2001；
     * 否则任务进入工作线程，完成/失败经 callback 通知（error 为 null 表示成功）。
     * 注意：异步路径不受 ocr.timeout-ms 约束，任务总会跑到自然结束。
     */
    public void recognizeAsync(byte[] imageBytes, String tier, int rotate, boolean autoRotate,
                               java.util.function.BiConsumer<TimedResults, Throwable> callback) {
        validateRotate(rotate);
        String resolved = (tier == null || tier.isBlank()) ? engineManager.getDefaultTier() : tier.toLowerCase();
        try {
            executor.submit(() -> {
                long start = System.currentTimeMillis();
                try {
                    List<PPOcrV6Result> results = metered(tier, () -> {
                        Mat image = decodeBytes(imageBytes);
                        try {
                            checkPixels(image);
                            return recognizeOriented(image, tier, rotate, autoRotate);
                        } finally {
                            image.release();
                        }
                    });
                    callback.accept(new TimedResults(resolved, results, System.currentTimeMillis() - start), null);
                } catch (Throwable t) {
                    callback.accept(null, t);
                }
                return null;
            });
        } catch (OcrException e) {
            if (e.getErrorCode() == ErrorCode.RATE_LIMITED) {
                meterRegistry.counter("ocr.rejected", "reason", "rejected").increment();
            }
            throw e;
        }
    }

    /**
     * 识别本地磁盘上的图片文件（demo/调试用途，不支持旋转参数）。
     */
    public List<PPOcrV6Result> recognizeFile(String path, String tier) {
        return metered(tier, () -> executor.execute(() -> {
            Mat image = Imgcodecs.imread(path);
            if (image.empty()) {
                throw new OcrException(ErrorCode.INVALID_PARAM, "图片不存在或无法读取: " + path);
            }
            try {
                checkPixels(image);
                return engineManager.getEngine(tier).run(image);
            } finally {
                image.release();
            }
        }));
    }

    /** 在工作线程内：先按 rotate/autoRotate 转正，再识别。 */
    private List<PPOcrV6Result> recognizeOriented(Mat image, String tier, int rotate, boolean autoRotate) {
        if (autoRotate) {
            return recognizeAutoRotate(image, tier);
        }
        Mat oriented = applyRotate(image, rotate);
        try {
            return engineManager.getEngine(tier).run(oriented);
        } finally {
            if (oriented != image) {
                oriented.release();
            }
        }
    }

    /**
     * 四方向自动试探：tiny 档各跑一次，按「文本长度加权平均置信度」选最优方向；
     * 请求档次为 tiny 时直接复用试探结果，否则用最优方向再跑请求档。
     */
    private List<PPOcrV6Result> recognizeAutoRotate(Mat image, String tier) {
        OcrEngine probe = engineManager.getEngine("tiny");
        int bestRotation = 0;
        double bestScore = -1;
        List<PPOcrV6Result> bestProbeResults = List.of();
        for (int rotation : AUTO_ROTATIONS) {
            Mat oriented = applyRotate(image, rotation);
            try {
                List<PPOcrV6Result> results = probe.run(oriented);
                double score = weightedScore(results);
                if (score > bestScore) {
                    bestScore = score;
                    bestRotation = rotation;
                    bestProbeResults = results;
                }
            } finally {
                if (oriented != image) {
                    oriented.release();
                }
            }
        }
        log.info("autoRotate 选定方向 {}°（加权置信度 {}）", bestRotation, String.format("%.3f", bestScore));
        String resolved = (tier == null || tier.isBlank()) ? engineManager.getDefaultTier() : tier.toLowerCase();
        if ("tiny".equals(resolved)) {
            return bestProbeResults;
        }
        Mat oriented = applyRotate(image, bestRotation);
        try {
            return engineManager.getEngine(tier).run(oriented);
        } finally {
            if (oriented != image) {
                oriented.release();
            }
        }
    }

    /** 文本长度加权的平均置信度；无识别结果记 0 分。 */
    private static double weightedScore(List<PPOcrV6Result> results) {
        double weightedSum = 0;
        long totalLen = 0;
        for (PPOcrV6Result r : results) {
            int len = r.text().length();
            weightedSum += r.score() * len;
            totalLen += len;
        }
        return totalLen == 0 ? 0 : weightedSum / totalLen;
    }

    /** 顺时针旋转；0° 直接返回原 Mat（调用方按引用相等判断是否需要释放）。 */
    private static Mat applyRotate(Mat image, int rotate) {
        if (rotate == 0) {
            return image;
        }
        int code = switch (rotate) {
            case 90 -> Core.ROTATE_90_CLOCKWISE;
            case 180 -> Core.ROTATE_180;
            case 270 -> Core.ROTATE_90_COUNTERCLOCKWISE;
            default -> throw new OcrException(ErrorCode.INVALID_PARAM, "rotate 仅支持 0/90/180/270");
        };
        Mat rotated = new Mat();
        Core.rotate(image, rotated, code);
        return rotated;
    }

    private static void validateRotate(int rotate) {
        if (rotate != 0 && rotate != 90 && rotate != 180 && rotate != 270) {
            throw new OcrException(ErrorCode.INVALID_PARAM, "rotate 仅支持 0/90/180/270，收到: " + rotate);
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
