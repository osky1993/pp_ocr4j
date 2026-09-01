package com.example.ppocr4j.parser.mrz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Year;
import java.util.regex.Pattern;

/**
 * MRZ 日期解析：{@code YYMMDD} → {@code yyyy-MM-dd}。
 */
public final class MrzDates {

    private static final Logger log = LoggerFactory.getLogger(MrzDates.class);

    /** MRZ 日期：YYMMDD。 */
    private static final Pattern MRZ_DATE = Pattern.compile("\\d{6}");

    private MrzDates() {
    }

    /**
     * 解析 MRZ 日期，并推断两位年份的世纪。
     *
     * <p>世纪推断规则：
     * <ul>
     *   <li>{@code pastOnly=true}（出生日期）——不可能在未来，{@code 20YY} 超过今年则取 {@code 19YY}；</li>
     *   <li>{@code pastOnly=false}（有效期）——一律取 {@code 20YY}。机读证件 1980 年代才出现，
     *       2000 年前签发的早已过期，不存在需要解释成 19YY 的有效期。</li>
     * </ul>
     *
     * @param yymmdd    MRZ 原文 6 位；null 或格式非法返回 null
     * @param pastOnly  true=该日期必定在过去（出生日期）
     * @param logPrefix 日志前缀（如「护照解析」）
     * @return {@code yyyy-MM-dd}；无法解析时返回 null
     */
    public static String parse(String yymmdd, boolean pastOnly, String logPrefix) {
        if (yymmdd == null || !MRZ_DATE.matcher(yymmdd).matches()) {
            return null;
        }
        int yy = Integer.parseInt(yymmdd.substring(0, 2));
        int mm = Integer.parseInt(yymmdd.substring(2, 4));
        int dd = Integer.parseInt(yymmdd.substring(4, 6));
        if (mm < 1 || mm > 12 || dd < 1 || dd > 31) {
            log.warn("{}：MRZ 日期 \"{}\" 月/日越界，置 null", logPrefix, yymmdd);
            return null;
        }
        int year = 2000 + yy;
        if (pastOnly && year > Year.now().getValue()) {
            year = 1900 + yy;
        }
        return String.format("%04d-%02d-%02d", year, mm, dd);
    }
}
