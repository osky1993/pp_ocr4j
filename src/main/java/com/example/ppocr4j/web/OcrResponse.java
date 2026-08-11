package com.example.ppocr4j.web;

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;

import java.util.List;

/**
 * OCR 接口返回结构。box 为文字框四个顶点坐标 [[x,y] * 4]，按阅读顺序排序。
 */
public record OcrResponse(String source, String tier, int count, long costMs, List<Item> results) {

    public record Item(String text, float score, List<List<Integer>> box) {
        static Item from(PPOcrV6Result r) {
            return new Item(r.text(), r.score(), r.boxAsNestedList());
        }
    }

    public static OcrResponse of(String source, String tier, List<PPOcrV6Result> results, long costMs) {
        List<Item> items = results.stream().map(Item::from).toList();
        return new OcrResponse(source, tier, items.size(), costMs, items);
    }
}
