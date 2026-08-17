package com.example.ppocr4j.web;

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;

import java.util.List;

/**
 * OCR 接口返回结构。box 为文字框四个顶点坐标 [[x,y] * 4]，按阅读顺序排序。
 */
public record OcrResponse(String source, String tier, int count, long costMs,
                          String fullText, List<Item> results) {

    /**
     * 单条识别结果项。
     *
     * @param text 识别到的文字内容
     * @param score 置信度分数，范围约 0~1
     * @param box 文字框四点坐标，结构为 {@code [[x,y],[x,y],[x,y],[x,y]}；
     *            rotatedDegrees 非 0 时坐标基于「转正后」的图像
     * @param rotatedDegrees 文档方向分类对原图应用的顺时针旋转角度（0/90/180/270），
     *                       未启用 doc_ori 或图片本身正向时为 0
     */
    public record Item(String text, float score, List<List<Integer>> box, int rotatedDegrees) {
        static Item from(PPOcrV6Result r) {
            return new Item(r.text(), r.score(), r.boxAsNestedList(), r.rotatedDegrees());
        }
    }

    /**
     * 构造标准 API 响应对象。
     *
     * @param source 来源（文件名或路径）
     * @param tier 实际使用模型档次
     * @param results 引擎原始结果
     * @param costMs 本次识别耗时，毫秒
     */
    public static OcrResponse of(String source, String tier, List<PPOcrV6Result> results, long costMs) {
        List<Item> items = results.stream().map(Item::from).toList();
        // fullText：按阅读顺序换行拼接的整页文本，供只关心全文的调用方直接取用
        String fullText = results.stream().map(PPOcrV6Result::text)
                .collect(java.util.stream.Collectors.joining("\n"));
        return new OcrResponse(source, tier, items.size(), costMs, fullText, items);
    }
}
