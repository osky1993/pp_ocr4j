package com.example.ppocr4j.web;

import com.example.ppocr4j.service.OcrParseService;

import java.util.List;
import java.util.Map;

/**
 * 结构化提取接口返回。
 *
 * @param source     来源（文件名或 base64）
 * @param docType    规范化后的证件类型
 * @param tier       实际使用模型档次
 * @param costMs     总耗时（OCR + 解析）
 * @param fields     业务字段（各证件类型字段见 README；未识别到的字段为 null）
 * @param fieldBoxes 字段名 → 命中文本框四点坐标列表（基于转正后图像），供可视化定位
 * @param results    OCR 原始逐行结果（含 rotatedDegrees），供审计与兜底
 */
public record ParseResponse(String source, String docType, String tier, long costMs,
                            Map<String, Object> fields,
                            Map<String, List<int[][]>> fieldBoxes,
                            List<OcrResponse.Item> results) {

    public static ParseResponse of(String source, String tier, OcrParseService.Outcome outcome, long costMs) {
        List<OcrResponse.Item> items = outcome.rawResults().stream().map(OcrResponse.Item::from).toList();
        return new ParseResponse(source, outcome.docType(), tier, costMs,
                outcome.fields(), outcome.fieldBoxes(), items);
    }
}
