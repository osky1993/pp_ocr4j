package com.example.ppocr4j.parser.validate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 中文大写金额解析测试。
 *
 * <p>这是发票金额自校验的基础，解析错了会给出错误的勾稽结论——
 * 比不校验更糟，所以边界情况要覆盖到位。
 */
class ChineseAmountTest {

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource({
            "壹佰贰拾叁元肆角伍分,      123.45",
            "叁仟元整,                3000.00",
            "壹万元整,                10000.00",
            "壹元,                    1.00",
            "零元整,                  0.00",
            "玖角,                    0.90",
            "伍分,                    0.05",
            "壹角伍分,                0.15",
            "壹佰元整,                100.00",
            "壹仟贰佰叁拾肆元伍角陆分, 1234.56",
            "壹万零伍拾元整,          10050.00",
            "壹亿元整,                100000000.00",
            "贰亿叁仟万元整,          230000000.00",
    })
    void parsesUppercaseAmounts(String text, String expected) {
        assertThat(ChineseAmount.parse(text)).isEqualByComparingTo(new BigDecimal(expected));
    }

    @Test
    void toleratesPrefixesAndSuffixes() {
        assertThat(ChineseAmount.parse("人民币壹佰元整")).isEqualByComparingTo("100.00");
        assertThat(ChineseAmount.parse("¥壹佰元整")).isEqualByComparingTo("100.00");
        assertThat(ChineseAmount.parse("壹佰元正")).isEqualByComparingTo("100.00");
        assertThat(ChineseAmount.parse("壹 佰 元 整")).isEqualByComparingTo("100.00");
        // 「圆」的写法
        assertThat(ChineseAmount.parse("壹佰圆整")).isEqualByComparingTo("100.00");
    }

    @Test
    void acceptsLowercaseChineseNumerals() {
        // OCR 有时会把「壹」读成「一」
        assertThat(ChineseAmount.parse("一百二十三元四角五分")).isEqualByComparingTo("123.45");
        assertThat(ChineseAmount.parse("三千元整")).isEqualByComparingTo("3000.00");
    }

    @Test
    void handlesOmittedLeadingOne() {
        // 「拾伍元」= 15 元，口语里省略了前导的「壹」
        assertThat(ChineseAmount.parse("拾伍元整")).isEqualByComparingTo("15.00");
        assertThat(ChineseAmount.parse("十五元整")).isEqualByComparingTo("15.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "壹佰XX元", "壹佰元肆X"})
    void returnsNullForUnparseableText(String bad) {
        // 宁可返回 null 也不猜——猜错会让金额勾稽给出错误结论
        assertThat(ChineseAmount.parse(bad)).isNull();
    }

    @Test
    void returnsNullForNull() {
        assertThat(ChineseAmount.parse(null)).isNull();
        assertThat(ChineseAmount.parseNumeric(null)).isNull();
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource({
            "1234.56,      1234.56",
            "¥1234.56,     1234.56",
            "1234.56元,    1234.56",
            "1234.56,      1234.56",
            "100,          100",
    })
    void parsesNumericAmounts(String text, String expected) {
        assertThat(ChineseAmount.parseNumeric(text)).isEqualByComparingTo(new BigDecimal(expected));
    }

    @Test
    void stripsThousandSeparators() {
        assertThat(ChineseAmount.parseNumeric("1,234.56")).isEqualByComparingTo("1234.56");
    }

    @Test
    void rejectsNumericWithMultipleDots() {
        assertThat(ChineseAmount.parseNumeric("12.34.56")).isNull();
    }

    @Test
    void comparesAmountsWithOneCentTolerance() {
        assertThat(ChineseAmount.equalAmount(new BigDecimal("100.00"), new BigDecimal("100.00"))).isTrue();
        assertThat(ChineseAmount.equalAmount(new BigDecimal("100.00"), new BigDecimal("100.01"))).isTrue();
        assertThat(ChineseAmount.equalAmount(new BigDecimal("100.00"), new BigDecimal("100.02"))).isFalse();
        assertThat(ChineseAmount.equalAmount(null, new BigDecimal("100.00"))).isFalse();
    }

    @Test
    void detectsOcrDigitConfusionViaUpperLowerMismatch() {
        // 这正是这个组件存在的意义：小写金额的 3 被读成 8，
        // 大写「叁」字形独特不会读错，两者一比就发现了
        BigDecimal upper = ChineseAmount.parse("叁佰元整");
        BigDecimal lowerMisread = ChineseAmount.parseNumeric("800.00");
        assertThat(ChineseAmount.equalAmount(upper, lowerMisread)).isFalse();
    }
}
