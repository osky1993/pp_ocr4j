package com.example.ppocr4j.parser.mrz;

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;

import java.util.List;

/**
 * 已定位的机读区：版式 + 各行文本 + 各行的来源 OCR 框。
 *
 * <p>某一行可能没被 OCR 识别到（检测漏检、图片裁切），此时 {@code lines} 里对应位置为
 * null。所有取值方法都对 null 与越界安全返回 null，让解析器可以「有多少解析多少」，
 * 而不是整份 MRZ 一起失败。
 *
 * @param format    版式
 * @param lines     各行清洗后文本，长度等于 {@code format.lineCount()}，未找到的行为 null
 * @param lineBoxes 各行的来源 OCR 框（一行可能由多个框拼接而成）
 */
public record MrzDocument(MrzFormat format, List<String> lines, List<List<PPOcrV6Result>> lineBoxes) {

    /**
     * 第 {@code line} 行文本。
     *
     * @return 该行文本；行不存在或未识别到时返回 null
     */
    public String line(int line) {
        return (line < 0 || line >= lines.size()) ? null : lines.get(line);
    }

    /**
     * 第 {@code line} 行的来源 OCR 框。
     *
     * @return 框列表；行不存在时返回空列表
     */
    public List<PPOcrV6Result> boxes(int line) {
        return (line < 0 || line >= lineBoxes.size()) ? List.of() : lineBoxes.get(line);
    }

    /**
     * 安全子串：行缺失或长度不足时返回 null，供定长解析在 OCR 截断时逐字段降级。
     *
     * @return 子串；越界返回 null
     */
    public String sub(int line, int from, int to) {
        String text = line(line);
        return (text == null || text.length() < to || from < 0 || from > to) ? null : text.substring(from, to);
    }

    /**
     * 取单个字符（用于读校验位）。
     *
     * @return 该位字符；越界返回 null
     */
    public Character charAt(int line, int pos) {
        String text = line(line);
        return (text == null || pos < 0 || pos >= text.length()) ? null : text.charAt(pos);
    }

    /**
     * 按字段规格取原始段（未剥离填充符）。
     *
     * @return 字段原文；越界返回 null
     */
    public String field(MrzFieldSpec spec) {
        return sub(spec.line(), spec.from(), spec.to());
    }

    /**
     * 按字段名取原始段。
     *
     * @return 字段原文；越界返回 null
     * @throws IllegalArgumentException 当前版式没有该字段
     */
    public String field(String name) {
        return field(format.field(name));
    }

    /** 该字段的来源 OCR 框，供 {@code fieldBoxes} 回填。 */
    public List<PPOcrV6Result> boxesOf(String name) {
        return boxes(format.field(name).line());
    }

    /** 是否至少定位到了一行。 */
    public boolean hasAnyLine() {
        return lines.stream().anyMatch(java.util.Objects::nonNull);
    }
}
