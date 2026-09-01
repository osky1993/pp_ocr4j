package com.example.ppocr4j.parser.mrz;

import java.util.Locale;

/**
 * MRZ 文本处理：字符归一化与填充符剥离。
 *
 * <p>从 {@code PassportParser} 抽出，供所有机读证件（TD1/TD2/TD3）复用。
 */
public final class MrzText {

    private MrzText() {
    }

    /**
     * MRZ 文本清洗：去空格、统一大写、把常见的 OCR 误识别字符还原为填充符。
     *
     * <p>只做保守替换（书名号/全角尖括号 → {@code <}），<b>不做</b> O↔0、I↔1 这类
     * 高风险猜测——那会把校验位算错，反而掩盖了识别质量问题。校验位的价值正在于
     * 它能发现 OCR 错字；替我们「猜」掉这些字符等于把安全网剪断。
     *
     * @param raw OCR 原始文本，可为 null
     * @return 清洗后文本；入参为 null 时返回空串
     */
    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toUpperCase(Locale.ROOT)
                .replace("«", "<<")
                .replace("‹", "<")
                .replace("＜", "<")
                .replaceAll("[\\s\\u00a0]", "");
    }

    /**
     * 去掉 MRZ 填充符 {@code <}。
     *
     * @param s 原始段，可为 null
     * @return 剥离后文本；为空或入参为 null 时返回 null（字段语义上的「没有值」）
     */
    public static String strip(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replace("<", "").trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 把 MRZ 姓名区的填充符还原成空格。
     *
     * @param names 姓名区原文（已去尾部填充）
     * @return 空格分隔的姓名；入参为 null 返回 null
     */
    public static String namesToSpaced(String names) {
        return names == null ? null : names.replaceAll("<+", " ").trim();
    }
}
