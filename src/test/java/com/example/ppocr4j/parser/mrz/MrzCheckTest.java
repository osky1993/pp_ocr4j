package com.example.ppocr4j.parser.mrz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ICAO 9303 校验位算法测试。
 *
 * <p>用例取自 ICAO Doc 9303 规范中的公开示例，以及荷兰官方 SPECIMEN 样本
 * （{@code test_images/passport-specimen.jpg}）实测读出的机读区——后者五个校验位
 * 全部自洽，是一组天然的端到端验证向量。
 */
class MrzCheckTest {

    /** 荷兰 SPECIMEN 的 MRZ 数据行，五个校验位全部自洽。 */
    private static final String SPECIMEN_L2 = "SPECI20142NLD6503101F2403096999999990<<<<<84";

    @ParameterizedTest(name = "checkDigit(\"{0}\") = {1}")
    @CsvSource({
            // 荷兰 SPECIMEN 的五段（与印刷校验位一致）
            "SPECI2014, 2",
            "650310,    1",
            "240309,    6",
            "999999990<<<<<, 8",
            // ICAO Doc 9303 规范示例
            "D23145890734, 9",
            "AB2134<<<,    5",
            // 全填充符与空串
            "<<<<<<<<<,    0",
            "'',           0",
    })
    void computesIcaoCheckDigit(String data, int expected) {
        assertThat(MrzCheck.computeCheckDigit(data)).isEqualTo(expected);
    }

    @Test
    void weightsCycleSevenThreeOne() {
        // 7-3-1 循环：单个 '1' 放在三个不同位置分别得 7、3、1
        assertThat(MrzCheck.computeCheckDigit("1")).isEqualTo(7);
        assertThat(MrzCheck.computeCheckDigit("01")).isEqualTo(3);
        assertThat(MrzCheck.computeCheckDigit("001")).isEqualTo(1);
        // 字母 A 取值 10
        assertThat(MrzCheck.computeCheckDigit("A")).isEqualTo(70 % 10);
    }

    @Test
    void fillerCharCountsAsZero() {
        assertThat(MrzCheck.computeCheckDigit("<12")).isEqualTo(MrzCheck.computeCheckDigit("012"));
    }

    @Test
    void verifyAcceptsMatchingDigit() {
        assertThat(MrzCheck.verify("SPECI2014", '2', "护照号", "测试")).isTrue();
    }

    @Test
    void verifyRejectsMismatchAndNonDigit() {
        assertThat(MrzCheck.verify("SPECI2014", '3', "护照号", "测试")).isFalse();
        // 填充符与字母都不是合法校验位
        assertThat(MrzCheck.verify("SPECI2014", '<', "护照号", "测试")).isFalse();
        assertThat(MrzCheck.verify("SPECI2014", 'X', "护照号", "测试")).isFalse();
    }

    @Test
    void validatesFullSpecimenDocument() {
        MrzDocument doc = new MrzDocument(MrzFormat.TD3,
                java.util.Arrays.asList("P<NLDDE<BRUIJN<<WILLEKE<LISELOTTE<<<<<<<<<<<", SPECIMEN_L2),
                java.util.List.of(java.util.List.of(), java.util.List.of()));

        assertThat(MrzCheck.validateAll(doc, "测试")).isTrue();
    }

    @Test
    void rejectsDocumentWithTamperedField() {
        // 出生日期 650310 → 650311，其校验位 1 随即失效
        String tampered = SPECIMEN_L2.replace("6503101", "6503111");
        MrzDocument doc = new MrzDocument(MrzFormat.TD3,
                java.util.Arrays.asList("P<NLDDE<BRUIJN<<WILLEKE<LISELOTTE<<<<<<<<<<<", tampered),
                java.util.List.of(java.util.List.of(), java.util.List.of()));

        assertThat(MrzCheck.validateAll(doc, "测试")).isFalse();
    }

    @Test
    void rejectsTruncatedLine() {
        MrzDocument doc = new MrzDocument(MrzFormat.TD3,
                java.util.Arrays.asList("P<NLDDE<BRUIJN<<WILLEKE", SPECIMEN_L2.substring(0, 30)),
                java.util.List.of(java.util.List.of(), java.util.List.of()));

        assertThat(MrzCheck.validateAll(doc, "测试")).isFalse();
    }

    @Test
    void rejectsMissingDataLine() {
        MrzDocument doc = new MrzDocument(MrzFormat.TD3,
                java.util.Arrays.asList("P<NLDDE<BRUIJN<<WILLEKE<LISELOTTE<<<<<<<<<<<", null),
                java.util.List.of(java.util.List.of(), java.util.List.of()));

        assertThat(MrzCheck.validateAll(doc, "测试")).isFalse();
    }
}
