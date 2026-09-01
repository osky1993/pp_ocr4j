package com.example.ppocr4j.parser.validate;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 中文大写金额解析。
 *
 * <p>用途是发票的<b>金额自校验</b>：票面同时印着大写与小写金额，两者应当相等。
 * 这是票据 OCR 少有的、不依赖任何外部信息的自校验机会——大写金额的字形
 * （壹贰叁肆伍陆柒捌玖）彼此差异极大，OCR 几乎不会把「叁」读成「捌」，
 * 而小写数字的 3 和 8、0 和 6 却很容易混淆。两者一比，就能发现小写金额被读错。
 */
public final class ChineseAmount {

    private ChineseAmount() {
    }

    /** 大写数字。同时接受小写形式，OCR 有时会把「壹」读成「一」。 */
    private static final Map<Character, Integer> DIGITS = Map.ofEntries(
            Map.entry('零', 0), Map.entry('壹', 1), Map.entry('贰', 2), Map.entry('叁', 3),
            Map.entry('肆', 4), Map.entry('伍', 5), Map.entry('陆', 6), Map.entry('柒', 7),
            Map.entry('捌', 8), Map.entry('玖', 9),
            Map.entry('〇', 0), Map.entry('一', 1), Map.entry('二', 2), Map.entry('三', 3),
            Map.entry('四', 4), Map.entry('五', 5), Map.entry('六', 6), Map.entry('七', 7),
            Map.entry('八', 8), Map.entry('九', 9),
            Map.entry('两', 2));

    /** 节内单位。 */
    private static final Map<Character, Integer> SMALL_UNITS = Map.of(
            '拾', 10, '十', 10, '佰', 100, '百', 100, '仟', 1000, '千', 1000);

    /** 节单位（万、亿）。 */
    private static final Map<Character, Long> BIG_UNITS = Map.of(
            '万', 10_000L, '萬', 10_000L, '亿', 100_000_000L, '億', 100_000_000L);

    /**
     * 把中文大写金额解析成数值。
     *
     * <p>支持形如「壹佰贰拾叁元肆角伍分」「叁仟元整」「壹万零伍拾元」的写法，
     * 允许前缀「人民币」「¥」以及末尾的「整」「正」。
     *
     * @param text 大写金额原文
     * @return 金额；无法解析时返回 null（不猜——猜错会让金额勾稽给出错误结论）
     */
    public static BigDecimal parse(String text) {
        if (text == null) {
            return null;
        }
        String s = text.replaceAll("[\\s¥￥]", "")
                .replace("人民币", "")
                .replaceAll("[整正]$", "");
        if (s.isEmpty()) {
            return null;
        }

        // 元/圆 之后是角分，之前是整数部分
        String integerPart;
        String fractionPart;
        int yuanIdx = indexOfAny(s, "元圆");
        if (yuanIdx >= 0) {
            integerPart = s.substring(0, yuanIdx);
            fractionPart = s.substring(yuanIdx + 1);
        } else if (indexOfAny(s, "角分") >= 0) {
            // 不足一元的金额没有「元」字（如「玖角」「壹角伍分」），整串都是角分
            integerPart = "";
            fractionPart = s;
        } else {
            integerPart = s;
            fractionPart = "";
        }

        Long integer = parseInteger(integerPart);
        if (integer == null) {
            return null;
        }
        BigDecimal result = BigDecimal.valueOf(integer);

        Integer fraction = parseFraction(fractionPart);
        if (fraction == null) {
            return null;
        }
        return result.add(BigDecimal.valueOf(fraction, 2)).setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    /** 解析整数部分（含万/亿分节）。 */
    private static Long parseInteger(String s) {
        if (s.isEmpty()) {
            return 0L;
        }
        long total = 0;      // 已结算的高位节
        long section = 0;    // 当前节累计
        long current = 0;    // 当前数字
        boolean sawDigit = false;

        for (char c : s.toCharArray()) {
            Integer digit = DIGITS.get(c);
            if (digit != null) {
                current = digit;
                sawDigit = true;
                continue;
            }
            Integer small = SMALL_UNITS.get(c);
            if (small != null) {
                // 「拾伍」这类省略了前导壹的写法
                section += (sawDigit ? current : 1) * small;
                current = 0;
                sawDigit = false;
                continue;
            }
            Long big = BIG_UNITS.get(c);
            if (big != null) {
                section += current;
                if (big == 100_000_000L) {
                    total = (total + section) * big;
                } else {
                    total += section * big;
                }
                section = 0;
                current = 0;
                sawDigit = false;
                continue;
            }
            return null;   // 出现无法识别的字符，宁可返回 null 也不猜
        }
        return total + section + current;
    }

    /** 解析角分部分，返回「分」为单位的整数。 */
    private static Integer parseFraction(String s) {
        if (s.isEmpty()) {
            return 0;
        }
        int jiao = 0;
        int fen = 0;
        int pending = 0;
        boolean sawDigit = false;
        for (char c : s.toCharArray()) {
            Integer digit = DIGITS.get(c);
            if (digit != null) {
                pending = digit;
                sawDigit = true;
                continue;
            }
            if (c == '角') {
                jiao = sawDigit ? pending : 0;
                pending = 0;
                sawDigit = false;
            } else if (c == '分') {
                fen = sawDigit ? pending : 0;
                pending = 0;
                sawDigit = false;
            } else {
                return null;
            }
        }
        return jiao * 10 + fen;
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 把小写金额文本解析成数值。
     *
     * @param text 形如 {@code ¥1234.56}、{@code 1,234.56}、{@code 1234.56元}
     * @return 金额；无法解析时返回 null
     */
    public static BigDecimal parseNumeric(String text) {
        if (text == null) {
            return null;
        }
        String s = text.replaceAll("[^0-9.]", "");
        if (s.isEmpty() || s.chars().filter(c -> c == '.').count() > 1) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 两个金额是否相等（容差 1 分，吸收四舍五入差异）。
     *
     * @return 两者都非 null 且差值不超过 0.01 时返回 true
     */
    public static boolean equalAmount(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return false;
        }
        return a.subtract(b).abs().compareTo(new BigDecimal("0.01")) <= 0;
    }
}
