package com.example.ppocr4j.parser.mrz;

/**
 * MRZ 单个字段的位置规格。
 *
 * <p>把「字段在第几行的哪一段、校验位在哪」声明成数据，而不是散落在各解析器里的
 * 硬编码下标——这样支持一种新版式只需往 {@link MrzFormat} 填一行数据，无需新逻辑。
 *
 * @param name       字段名（英文，程序用）
 * @param label      字段中文名（日志用）
 * @param line       所在行下标（0 起）
 * @param from       起始下标（含）
 * @param to         结束下标（不含）
 * @param checkLine  校验位所在行下标；{@code -1} 表示该字段无校验位
 * @param checkPos   校验位下标；{@code -1} 表示该字段无校验位
 */
public record MrzFieldSpec(String name, String label, int line, int from, int to,
                           int checkLine, int checkPos) {

    /** 无校验位字段的便捷构造。 */
    public static MrzFieldSpec of(String name, String label, int line, int from, int to) {
        return new MrzFieldSpec(name, label, line, from, to, -1, -1);
    }

    /** 带校验位字段的便捷构造（校验位与字段同行，这是三种版式的共同情况）。 */
    public static MrzFieldSpec checked(String name, String label, int line, int from, int to, int checkPos) {
        return new MrzFieldSpec(name, label, line, from, to, line, checkPos);
    }

    public boolean hasCheckDigit() {
        return checkLine >= 0 && checkPos >= 0;
    }
}
