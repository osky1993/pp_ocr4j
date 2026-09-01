package com.example.ppocr4j.parser;

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 护照解析器单元测试：直接喂构造好的 OCR 文本框，不依赖真实推理。
 *
 * <p>覆盖的重点是「OCR 出错时解析器怎么表现」——这些分支在正常样图上跑不到，
 * 却是生产环境最常见的情况。
 */
class PassportParserTest {

    private final PassportParser parser = new PassportParser();

    /** 造一个矩形文本框：左上 (x0,y0)、右下 (x1,y1)。 */
    private static PPOcrV6Result box(String text, int x0, int y0, int x1, int y1) {
        return new PPOcrV6Result(text, 0.99f, new int[][]{{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}}, 0);
    }

    /** 标准 TD3 机读区两行（荷兰 SPECIMEN 的真实内容，校验位自洽）。 */
    private static List<PPOcrV6Result> standardMrz() {
        return List.of(
                box("P<NLDDE<BRUIJN<<WILLEKE<LISELOTTE<<<<<<<<<<<", 100, 900, 900, 950),
                box("SPECI20142NLD6503101F2403096999999990<<<<<84", 100, 960, 900, 1010));
    }

    @Test
    void parsesStandardMrz() {
        PassportResult r = parser.parseResults(standardMrz());

        assertThat(r.getMrzValid()).isTrue();
        assertThat(r.getDocumentType()).isEqualTo("P");
        assertThat(r.getIssuingCountry()).isEqualTo("NLD");
        assertThat(r.getPassportNo()).isEqualTo("SPECI2014");
        assertThat(r.getNationality()).isEqualTo("NLD");
        assertThat(r.getSex()).isEqualTo("F");
        assertThat(r.getBirthDate()).isEqualTo("1965-03-10");
        assertThat(r.getExpiryDate()).isEqualTo("2024-03-09");
        assertThat(r.getPersonalNumber()).isEqualTo("999999990");
        // 分隔符完整时才切分姓/名
        assertThat(r.getSurname()).isEqualTo("DE BRUIJN");
        assertThat(r.getGivenNames()).isEqualTo("WILLEKE LISELOTTE");
        assertThat(r.getNameEn()).isEqualTo("DE BRUIJN WILLEKE LISELOTTE");
        assertThat(r.getFieldBoxes()).containsKeys("mrzLine1", "mrzLine2", "passportNo");
    }

    @Test
    void keepsNameEnButSkipsSurnameWhenSeparatorLost() {
        // tiny 档在真实样图上就是这样：BRUIJN<<WILLEKE 少读成 BRUIJN<WILLEKE
        List<PPOcrV6Result> results = List.of(
                box("P<NLDDE<BRUIJN<WILLEKE<LISELOTTE<<<", 100, 900, 900, 950),
                box("SPECI20142NLD6503101F2403096999999990<<<<<84", 100, 960, 900, 1010));

        PassportResult r = parser.parseResults(results);

        // 猜姓名边界会把复姓 DE BRUIJN 切错，因此宁可留空
        assertThat(r.getSurname()).isNull();
        assertThat(r.getGivenNames()).isNull();
        assertThat(r.getNameEn()).isEqualTo("DE BRUIJN WILLEKE LISELOTTE");
        // 第二行不受影响，其余字段照常可用
        assertThat(r.getMrzValid()).isTrue();
        assertThat(r.getPassportNo()).isEqualTo("SPECI2014");
    }

    @Test
    void joinsMrzLineSplitAcrossBoxes() {
        // 检测框把一行 MRZ 切成两段（尾部填充符单独成框），需按 y 聚类后按 x 拼接
        List<PPOcrV6Result> results = List.of(
                box("P<NLDDE<BRUIJN<<WILLEKE<LISELOTTE<<<", 100, 900, 800, 950),
                box("<<<<<<<", 810, 902, 900, 948),
                box("SPECI20142NLD6503101F2403096999999990<<<<<84", 100, 960, 900, 1010));

        PassportResult r = parser.parseResults(results);

        assertThat(r.getMrzLine1()).isEqualTo("P<NLDDE<BRUIJN<<WILLEKE<LISELOTTE<<<<<<<<<<");
        assertThat(r.getSurname()).isEqualTo("DE BRUIJN");
        assertThat(r.getGivenNames()).isEqualTo("WILLEKE LISELOTTE");
    }

    @Test
    void flagsMrzInvalidWhenCheckDigitMismatches() {
        // 把出生日期从 650310 改成 650311，其校验位 1 随即失效
        List<PPOcrV6Result> results = List.of(
                box("P<NLDDE<BRUIJN<<WILLEKE<LISELOTTE<<<<<<<<<<<", 100, 900, 900, 950),
                box("SPECI20142NLD6503111F2403096999999990<<<<<84", 100, 960, 900, 1010));

        PassportResult r = parser.parseResults(results);

        // 字段照常返回，但明确标记为不可信
        assertThat(r.getMrzValid()).isFalse();
        assertThat(r.getBirthDate()).isEqualTo("1965-03-11");
    }

    @Test
    void inferstCenturyForBirthDate() {
        // 出生日期不可能在未来：65 → 1965 而不是 2065
        PassportResult r = parser.parseResults(standardMrz());
        assertThat(r.getBirthDate()).startsWith("19");
        // 有效期一律按 20YY 解释
        assertThat(r.getExpiryDate()).startsWith("20");
    }

    @Test
    void returnsEmptyResultWithoutMrz() {
        PassportResult r = parser.parseResults(List.of(box("这不是护照", 0, 0, 100, 20)));

        assertThat(r.getMrzValid()).isNull();
        assertThat(r.getPassportNo()).isNull();
        assertThat(r.getRawResults()).hasSize(1);
    }

    // ==================================================================
    // 可视区（中国护照版面：标签在上、值在下，双列）
    // ==================================================================

    /**
     * 中国护照可视区版面，文本取自真实样图的 OCR 输出
     * （含 OCR 把空格吃掉导致的「日月粘连」：27 3月/MAR 2014 → 273月/MAR2014）。
     */
    private static List<PPOcrV6Result> chineseViz() {
        return List.of(
                box("性别/Sex", 100, 100, 250, 130),
                box("国籍/Nationality", 400, 100, 600, 130),
                box("男/M", 100, 140, 250, 175),
                box("中国/CHINESE", 400, 140, 600, 175),
                box("出生地点/Placeofbirth", 100, 200, 320, 230),
                box("签发日期/Dateofissue", 400, 200, 620, 230),
                box("广东/GUANGDONG", 100, 240, 320, 275),
                box("273月/MAR2014", 400, 240, 620, 275),
                box("签发机关/Authority", 100, 300, 320, 330),
                box("持照人签名/Bearer'ssignature", 400, 300, 650, 330),
                box("公安部出入境管理局", 100, 340, 350, 375));
    }

    @Test
    void parsesChineseVisualZone() {
        PassportResult r = parser.parseResults(chineseViz());

        // 值在标签正下方，不能取成同一行右侧的另一个标签
        assertThat(r.getSex()).isEqualTo("M");
        assertThat(r.getPlaceOfBirth()).isEqualTo("广东/GUANGDONG");
        assertThat(r.getAuthority()).isEqualTo("公安部出入境管理局");
        // 日月粘连的 273月/MAR2014 必须解成 27 日而不是 3 日或 273 日
        assertThat(r.getIssueDate()).isEqualTo("2014-03-27");
    }

    @Test
    void skipsNameCnWhenNoChinesePresent() {
        // 中文姓名缺失时不能把邻框的拼音/噪声当成中文姓名
        List<PPOcrV6Result> results = List.of(
                box("姓名/Name", 100, 100, 250, 130),
                box("E12345678", 100, 140, 300, 175));

        PassportResult r = parser.parseResults(results);

        assertThat(r.getNameCn()).isNull();
        assertThat(r.getFieldBoxes()).doesNotContainKey("nameCn");
    }
}
