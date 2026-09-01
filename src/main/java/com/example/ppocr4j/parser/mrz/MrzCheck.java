package com.example.ppocr4j.parser.mrz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ICAO 9303 校验位算法与校验流程。
 *
 * <p>机读证件的可靠性几乎全部来自这套校验位：字段是定长定位的，只要校验位对得上，
 * OCR 就极不可能同时把数据和校验位都读错成自洽的一组。这是 MRZ 路线优于
 * 标签定位的根本原因。
 */
public final class MrzCheck {

    private static final Logger log = LoggerFactory.getLogger(MrzCheck.class);

    private MrzCheck() {
    }

    /**
     * ICAO 9303 校验位算法：字符权值（数字取本身，A-Z 取 10~35，{@code <} 取 0）
     * 按 7-3-1 循环加权求和后取模 10。
     *
     * @param data 被校验的数据段
     * @return 0~9 的校验位数值
     */
    public static int computeCheckDigit(String data) {
        int[] weights = {7, 3, 1};
        int sum = 0;
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            int v;
            if (c == '<') {
                v = 0;
            } else if (c >= '0' && c <= '9') {
                v = c - '0';
            } else if (c >= 'A' && c <= 'Z') {
                v = c - 'A' + 10;
            } else {
                v = 0;
            }
            sum += v * weights[i % 3];
        }
        return sum % 10;
    }

    /**
     * 单个校验位比对。不匹配时记 warn 但不抛异常——字段仍要返回给调用方，
     * 由 {@code mrzValid=false} 提示需人工复核。
     *
     * @param data      被校验的数据段
     * @param expected  MRZ 中印刷的校验位字符
     * @param fieldName 字段名（日志用，如「护照号」）
     * @param logPrefix 日志前缀（如「护照解析」），便于区分是哪种证件
     * @return 匹配返回 true
     */
    public static boolean verify(String data, char expected, String fieldName, String logPrefix) {
        int actual = computeCheckDigit(data);
        if (expected == '<' || !Character.isDigit(expected) || actual != expected - '0') {
            log.warn("{}：MRZ {} 校验位不匹配（印刷 '{}'，算得 {}），字段仍返回但需人工复核",
                    logPrefix, fieldName, expected, actual);
            return false;
        }
        return true;
    }

    /**
     * 按版式规格校验一份 MRZ 的全部校验位（各字段校验位 + 综合校验位）。
     *
     * <p>短路求值会让第一个失败之后的校验位不再计算，从而丢失诊断信息，
     * 因此这里用不短路的 {@code &}——一次性把所有出问题的字段都记进日志。
     *
     * @param doc       已定位的 MRZ
     * @param logPrefix 日志前缀（如「护照解析」）
     * @return 全部通过返回 true；任一行长度不足或任一校验位不匹配返回 false
     */
    public static boolean validateAll(MrzDocument doc, String logPrefix) {
        MrzFormat format = doc.format();

        // 带校验位的行必须完整，否则下标会越界
        for (int line : format.linesCarryingCheckDigits()) {
            String text = doc.line(line);
            if (text == null || text.length() < format.lineLength()) {
                log.warn("{}：MRZ 第 {} 行长度 {} < {}，跳过校验位验证",
                        logPrefix, line + 1, text == null ? 0 : text.length(), format.lineLength());
                return false;
            }
        }

        boolean ok = true;
        for (MrzFieldSpec spec : format.fields()) {
            if (!spec.hasCheckDigit()) {
                continue;
            }
            String data = doc.field(spec);
            Character printed = doc.charAt(spec.checkLine(), spec.checkPos());
            if (data == null || printed == null) {
                ok = false;
                continue;
            }
            ok = ok & verify(data, printed, spec.label(), logPrefix);
        }

        MrzCompositeSpec composite = format.composite();
        StringBuilder sb = new StringBuilder();
        for (MrzCompositeSpec.Segment seg : composite.segments()) {
            String part = doc.sub(seg.line(), seg.from(), seg.to());
            if (part == null) {
                return false;
            }
            sb.append(part);
        }
        Character printed = doc.charAt(composite.checkLine(), composite.checkPos());
        ok = ok & (printed != null && verify(sb.toString(), printed, "综合", logPrefix));

        if (ok) {
            log.debug("{}：MRZ 校验位全部通过", logPrefix);
        }
        return ok;
    }
}
