package com.example.ppocr4j.parser;

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 往来港澳通行证解析器单元测试。
 *
 * <p>文本框内容取自公安部公开证件样本的真实 OCR 输出（tiny 与 small 档），
 * 包括 OCR 实际犯的错——标签错字、跨框切分、值合并。这些不是想象出来的边界情况。
 */
class HkMacaoPermitParserTest {

    private final HkMacaoPermitParser parser = new HkMacaoPermitParser();

    private static PPOcrV6Result box(String text, int x0, int y0, int x1, int y1) {
        return new PPOcrV6Result(text, 0.99f, new int[][]{{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}}, 0);
    }

    /** 机读码：证件号 CA3273201、有效期至 2029-01-17、出生 1981-08-03，四个校验位自洽。 */
    private static final String MRZ = "CSCA32732010<2901178<8108038<2";

    /** 官方样本卡的完整版面（按 small 档实测输出构造）。 */
    private static List<PPOcrV6Result> specimenLayout() {
        List<PPOcrV6Result> r = new ArrayList<>();
        r.add(box("往来港澳通行证", 150, 30, 640, 80));
        r.add(box("CA3273201", 720, 45, 980, 95));
        r.add(box("证件样本", 350, 145, 530, 190));
        r.add(box("ZHENGJIAN,YANGBEN", 350, 195, 640, 230));
        r.add(box("出生日期", 350, 265, 470, 295));
        r.add(box("性别", 630, 265, 700, 295));
        r.add(box("1981.08.03", 350, 300, 545, 340));
        r.add(box("女", 630, 300, 680, 340));
        r.add(box("有效期限", 350, 350, 470, 380));
        r.add(box("2019.01.18-2029.01.17", 350, 385, 790, 420));
        r.add(box("签发机关", 350, 435, 470, 465));
        r.add(box("签发地点", 725, 435, 845, 465));
        r.add(box("中华人民共和国出入境管理局1广东", 350, 470, 830, 505));
        r.add(box(MRZ, 60, 550, 960, 590));
        return r;
    }

    @Test
    void parsesSpecimenEndToEnd() {
        HkMacaoPermitResult r = parser.parseResults(specimenLayout());

        // 机读码字段（自带校验位，可靠性最高）
        assertThat(r.getMrzValid()).isTrue();
        assertThat(r.getMrzLine()).isEqualTo(MRZ);
        assertThat(r.getDocumentType()).isEqualTo("CS");
        assertThat(r.getPermitNo()).isEqualTo("CA3273201");
        assertThat(r.getBirthDate()).isEqualTo("1981-08-03");
        assertThat(r.getExpiryDate()).isEqualTo("2029-01-17");
        // 可视区字段（机读码里没有）
        assertThat(r.getValidFrom()).isEqualTo("2019-01-18");
        assertThat(r.getNameCn()).isEqualTo("证件样本");
        assertThat(r.getNameEn()).isEqualTo("ZHENGJIAN, YANGBEN");
        assertThat(r.getSex()).isEqualTo("F");
        assertThat(r.getIssuingAuthority()).isEqualTo("中华人民共和国出入境管理局");
        assertThat(r.getPlaceOfIssue()).isEqualTo("广东");

        assertThat(r.getFieldBoxes()).containsKeys("mrzLine", "permitNo", "nameCn", "nameEn");
    }

    /**
     * 回归测试：tiny 档会把「签发机关」读成「签发机美」——四个汉字、不在排除表里，
     * 早期版本的全版面扫描把它当成了中文姓名填进来。
     *
     * <p>假阳性（填一个看起来合理的错值）比留空危险得多，调用方根本看不出来。
     * 现在靠「中文姓名必须在拼音姓名正上方」的位置约束挡住。
     */
    @Test
    void doesNotMistakeGarbledLabelForChineseName() {
        List<PPOcrV6Result> results = new ArrayList<>(specimenLayout());
        results.removeIf(b -> b.text().equals("签发机关"));
        results.add(box("签发机美", 350, 435, 470, 465));   // tiny 档实际读出的错字

        HkMacaoPermitResult r = parser.parseResults(results);

        assertThat(r.getNameCn()).isEqualTo("证件样本");
        assertThat(r.getNameCn()).isNotEqualTo("签发机美");
    }

    /** 拼音姓名会被 tiny 档切成两个框，需按行拼接后才能匹配。 */
    @Test
    void joinsPinyinNameSplitAcrossBoxes() {
        List<PPOcrV6Result> results = new ArrayList<>(specimenLayout());
        results.removeIf(b -> b.text().equals("ZHENGJIAN,YANGBEN"));
        results.add(box("ZHENGJIAN,", 350, 195, 520, 230));
        results.add(box("YANGBEN", 530, 196, 640, 229));

        HkMacaoPermitResult r = parser.parseResults(results);

        assertThat(r.getNameEn()).isEqualTo("ZHENGJIAN, YANGBEN");
        assertThat(r.getNameCn()).isEqualTo("证件样本");
    }

    /**
     * 签发机关与签发地点被 OCR 合并进同一个框，中间竖线被读成数字 1。
     * 需要按「局」字边界拆开，否则两个字段都是错的。
     */
    @Test
    void splitsMergedAuthorityAndPlace() {
        HkMacaoPermitResult r = parser.parseResults(specimenLayout());

        assertThat(r.getIssuingAuthority()).isEqualTo("中华人民共和国出入境管理局");
        assertThat(r.getPlaceOfIssue()).isEqualTo("广东");
    }

    @Test
    void flagsMrzInvalidWhenCheckDigitMismatches() {
        // 出生日期 810803 → 810804，其校验位 8 随即失效
        List<PPOcrV6Result> results = new ArrayList<>(specimenLayout());
        results.removeIf(b -> b.text().equals(MRZ));
        results.add(box(MRZ.replace("8108038", "8108048"), 60, 550, 960, 590));

        HkMacaoPermitResult r = parser.parseResults(results);

        assertThat(r.getMrzValid()).isFalse();
        // 字段照常返回，由 mrzValid 提示需人工复核
        assertThat(r.getBirthDate()).isEqualTo("1981-08-04");
        assertThat(r.getPermitNo()).isEqualTo("CA3273201");
    }

    /** 机读码整行漏检时，可视区必须顶上——证件号与出生日期在版面上也印着。 */
    @Test
    void fallsBackToVisualZoneWithoutMrz() {
        List<PPOcrV6Result> results = new ArrayList<>(specimenLayout());
        results.removeIf(b -> b.text().equals(MRZ));

        HkMacaoPermitResult r = parser.parseResults(results);

        assertThat(r.getMrzValid()).isNull();
        assertThat(r.getMrzLine()).isNull();
        assertThat(r.getPermitNo()).isEqualTo("CA3273201");
        assertThat(r.getBirthDate()).isEqualTo("1981-08-03");
        assertThat(r.getValidFrom()).isEqualTo("2019-01-18");
        assertThat(r.getExpiryDate()).isEqualTo("2029-01-17");
        assertThat(r.getNameCn()).isEqualTo("证件样本");
    }

    /** 出生日期兜底不能把有效期区间里的日期误当成出生日期。 */
    @Test
    void doesNotTakeValidityRangeAsBirthDate() {
        List<PPOcrV6Result> results = new ArrayList<>(specimenLayout());
        results.removeIf(b -> b.text().equals(MRZ) || b.text().equals("1981.08.03"));

        HkMacaoPermitResult r = parser.parseResults(results);

        assertThat(r.getBirthDate()).as("版面上没有独立的出生日期时应留空，而不是拿区间里的日期充数").isNull();
        assertThat(r.getValidFrom()).isEqualTo("2019-01-18");
    }

    @Test
    void acceptsBothOldAndNewPermitNumberFormats() {
        // 2018-12-03 前：C + 8 位数字
        List<PPOcrV6Result> old = List.of(box("C12345678", 720, 45, 980, 95));
        assertThat(parser.parseResults(old).getPermitNo()).isEqualTo("C12345678");
        // 之后：C + 字母 + 7 位数字
        List<PPOcrV6Result> recent = List.of(box("CA3273201", 720, 45, 980, 95));
        assertThat(parser.parseResults(recent).getPermitNo()).isEqualTo("CA3273201");
    }

    @Test
    void returnsEmptyResultForUnrelatedImage() {
        HkMacaoPermitResult r = parser.parseResults(List.of(box("这不是通行证", 0, 0, 100, 20)));

        assertThat(r.getMrzValid()).isNull();
        assertThat(r.getPermitNo()).isNull();
        assertThat(r.getRawResults()).hasSize(1);
    }

    @Test
    void returnsEmptyResultForEmptyInput() {
        HkMacaoPermitResult r = parser.parseResults(List.of());

        assertThat(r.getPermitNo()).isNull();
        assertThat(r.getRawResults()).isEmpty();
    }
}
