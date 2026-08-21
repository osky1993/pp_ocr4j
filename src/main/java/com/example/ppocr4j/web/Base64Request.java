package com.example.ppocr4j.web;

import com.example.ppocr4j.exception.OcrException;

import java.util.Base64;

/**
 * base64 图片识别请求体（同步接口与异步任务接口共用）。
 *
 * <p>字段说明：
 * <ul>
 *   <li>image：图片 base64，支持裸串与 data URL</li>
 *   <li>tier：模型档次（可选）</li>
 *   <li>rotate：手动旋转角度（可选）</li>
 *   <li>autoRotate：是否开启四方向自动试探</li>
 * </ul>
 * </p>
 */
public record Base64Request(String image, String tier, Integer rotate, Boolean autoRotate) {

    /**
     * 校验并解码 image 字段。
     *
     * <p>支持裸 Base64 与 data URL 两种格式，失败时抛 INVALID_PARAM，避免把解码异常泄漏为 500。</p>
     */
    public byte[] toBytes() {
        if (image == null || image.isBlank()) {
            throw new OcrException(ErrorCode.INVALID_PARAM, "image 字段为空");
        }
        String payload = image;
        int comma = payload.indexOf(',');
        if (payload.startsWith("data:") && comma > 0) {
            payload = payload.substring(comma + 1);
        }
        try {
            return Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new OcrException(ErrorCode.INVALID_PARAM, "image 不是合法的 base64 字符串");
        }
    }

    /** rotate 缺省值回退 0，单位度，支持值见 OcrService validateRotate。 */
    public int rotateOrDefault() {
        return rotate == null ? 0 : rotate;
    }

    /** autoRotate 缺省值回退 false；仅检查空值与 Boolean.TRUE。 */
    public boolean autoRotateOrDefault() {
        return Boolean.TRUE.equals(autoRotate);
    }
}
