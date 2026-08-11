package com.example.ppocr4j.web;

import com.example.ppocr4j.exception.OcrException;

import java.util.Base64;

/**
 * base64 图片识别请求体（同步接口与异步任务接口共用）。
 * image 支持裸 base64 或 data URL（data:image/png;base64,...）。
 */
public record Base64Request(String image, String tier, Integer rotate, Boolean autoRotate) {

    /** 校验并解码 image 字段。 */
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

    public int rotateOrDefault() {
        return rotate == null ? 0 : rotate;
    }

    public boolean autoRotateOrDefault() {
        return Boolean.TRUE.equals(autoRotate);
    }
}
