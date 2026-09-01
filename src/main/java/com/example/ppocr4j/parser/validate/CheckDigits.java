package com.example.ppocr4j.parser.validate;

import java.util.Locale;

/**
 * 证件与票据号码的校验位算法。
 *
 * <p><b>为什么需要这一层</b>：mica-ppocr-structured 内置的六个解析器全部是
 * 「标签定位 + 裸正则」，没有任何一处校验位验证（全 jar 内搜 {@code 7064|32100|checksum}
 * 只能命中一条注释）。这意味着 OCR 把身份证号里的 {@code 0} 读成 {@code 8}、
 * 把统一社会信用代码里的 {@code 1} 读成 {@code I}，解析器都会照单全收，
 * 调用方拿到一个格式完全正确但内容错误的号码。
 *
 * <p>校验位正是为发现这类错误而设计的。本类只做纯算法，不涉及业务字段，
 * 便于单独覆盖测试；业务分派见 {@link ParseValidator}。
 */
public final class CheckDigits {

    private CheckDigits() {
    }

    // ==================================================================
    // 居民身份证号：GB 11643-1999，ISO 7064 MOD 11-2
    // ==================================================================

    /** 前 17 位的加权因子。 */
    private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    /** 余数 0~10 对应的校验码。 */
    private static final char[] ID_CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    /**
     * 校验 18 位居民身份证号码（ISO 7064 MOD 11-2）。
     *
     * <p>驾驶证号在中国就是持证人的身份证号，因此驾驶证也用这个方法校验。
     *
     * @param id 待校验号码，允许末位小写 x
     * @return 校验通过返回 true；null、长度不对、含非法字符、校验位不匹配一律返回 false
     */
    public static boolean isValidIdNumber(String id) {
        if (id == null || id.length() != 18) {
            return false;
        }
        String s = id.toUpperCase(Locale.ROOT);
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            sum += (c - '0') * ID_WEIGHTS[i];
        }
        char last = s.charAt(17);
        // X 只能出现在末位——上游正则 [0-9X]{18} 允许它出现在任意位置，是个真实缺陷
        if (last != 'X' && (last < '0' || last > '9')) {
            return false;
        }
        return last == ID_CHECK_CODES[sum % 11];
    }

    /**
     * 校验身份证号里的出生日期段（第 7~14 位）是否是一个合法日期，且不在未来。
     *
     * <p>校验位能发现随机错字，但对「日期段被读成另一个同样能通过校验位的值」无能为力，
     * 因此再加一道语义校验。
     *
     * @return 合法返回 true；号码长度不足或日期非法返回 false
     */
    public static boolean hasValidBirthSegment(String id) {
        if (id == null || id.length() != 18) {
            return false;
        }
        try {
            java.time.LocalDate date = java.time.LocalDate.parse(
                    id.substring(6, 14), java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            return !date.isAfter(java.time.LocalDate.now()) && date.getYear() >= 1850;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 从身份证号推断性别：第 17 位奇数为男、偶数为女。
     *
     * @return {@code "M"} / {@code "F"}；号码非法时返回 null
     */
    public static String genderFromIdNumber(String id) {
        if (id == null || id.length() != 18) {
            return null;
        }
        char c = id.charAt(16);
        if (c < '0' || c > '9') {
            return null;
        }
        return (c - '0') % 2 == 1 ? "M" : "F";
    }

    // ==================================================================
    // 统一社会信用代码：GB 32100-2015
    // ==================================================================

    /**
     * 合法字符集，共 31 个：数字 0-9 加大写字母，<b>剔除 I、O、S、V、Z</b>。
     *
     * <p>剔除这五个字母正是为了避免与 1、0、5、U、2 混淆——而 OCR 最容易犯的
     * 恰恰就是这类错误。上游的正则用的是 {@code [0-9A-Z]} 全字母表，
     * 等于把这层设计好的防护拆掉了。
     */
    private static final String CREDIT_CHARSET = "0123456789ABCDEFGHJKLMNPQRTUWXY";
    /** 前 17 位的加权因子。 */
    private static final int[] CREDIT_WEIGHTS = {
            1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};

    /**
     * 校验 18 位统一社会信用代码（GB 32100-2015）。
     *
     * @param code 待校验代码
     * @return 校验通过返回 true；null、长度不足 18、含非法字符、校验位不匹配一律返回 false
     */
    public static boolean isValidCreditCode(String code) {
        if (code == null || code.length() != 18) {
            return false;
        }
        String s = code.toUpperCase(Locale.ROOT);
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            int v = CREDIT_CHARSET.indexOf(s.charAt(i));
            if (v < 0) {
                return false;
            }
            sum += v * CREDIT_WEIGHTS[i];
        }
        int expected = 31 - (sum % 31);
        if (expected == 31) {
            expected = 0;
        }
        int actual = CREDIT_CHARSET.indexOf(s.charAt(17));
        return actual >= 0 && actual == expected;
    }

    /**
     * 代码里是否出现了 GB 32100 明确排除的字母（I/O/S/V/Z）。
     *
     * <p>出现这些字母基本可以断定是 OCR 误识（1→I、0→O、5→S、U→V、2→Z），
     * 值得在校验结果里单独提示，比笼统说一句「校验位不匹配」更有助于定位问题。
     */
    public static boolean containsExcludedLetters(String code) {
        if (code == null) {
            return false;
        }
        return code.toUpperCase(Locale.ROOT).chars().anyMatch(c -> "IOSVZ".indexOf(c) >= 0);
    }

    // ==================================================================
    // 银行卡号：Luhn（ISO/IEC 7812-1）
    // ==================================================================

    /**
     * Luhn 校验（银行卡号通用）。
     *
     * @param number 待校验卡号，允许含空格与连字符
     * @return 校验通过返回 true；null、长度不在 12~19 位、含非数字、校验失败一律返回 false
     */
    public static boolean isValidLuhn(String number) {
        if (number == null) {
            return false;
        }
        String digits = number.replaceAll("[\\s-]", "");
        if (digits.length() < 12 || digits.length() > 19 || !digits.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int sum = 0;
        boolean doubling = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubling) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubling = !doubling;
        }
        return sum % 10 == 0;
    }
}
