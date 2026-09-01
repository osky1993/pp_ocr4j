package com.example.ppocr4j.parser.mrz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 版式规格表的结构自检。
 *
 * <p><b>为什么需要这组测试</b>：{@link MrzFormat} 里 TD1/TD2 的字段位置表是按 ICAO 9303
 * 规范填的数据，但当前只有 TD3 有真实样图跑通（护照）。填了数据却没验证等于埋了
 * 未验证代码——这组结构自检至少能保证下标不越界、不重叠、校验位落在合法位置，
 * 让迭代 2 启用 TD1 时是从一个已知自洽的规格表开始，而不是从一堆可能抄错的数字开始。
 */
class MrzFormatTest {

    @ParameterizedTest
    @EnumSource(MrzFormat.class)
    void everyFieldStaysInsideItsLine(MrzFormat format) {
        List<String> violations = new ArrayList<>();
        for (MrzFieldSpec spec : format.fields()) {
            if (spec.line() < 0 || spec.line() >= format.lineCount()) {
                violations.add(spec.name() + " 行下标 " + spec.line() + " 越界");
            }
            if (spec.from() < 0 || spec.to() > format.lineLength() || spec.from() >= spec.to()) {
                violations.add(spec.name() + " 区间 [" + spec.from() + "," + spec.to() + ") 非法");
            }
            if (spec.hasCheckDigit()) {
                if (spec.checkLine() < 0 || spec.checkLine() >= format.lineCount()) {
                    violations.add(spec.name() + " 校验位行下标越界");
                }
                if (spec.checkPos() < 0 || spec.checkPos() >= format.lineLength()) {
                    violations.add(spec.name() + " 校验位下标 " + spec.checkPos() + " 越界");
                }
            }
        }
        assertThat(violations).as("%s 字段规格越界", format).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(MrzFormat.class)
    void dataFieldsDoNotOverlapEachOther(MrzFormat format) {
        // 姓名区在 TD1 独占一行、在 TD2/TD3 与类型/签发国同行但不相交，
        // 因此所有字段两两之间都不该有重叠
        List<String> overlaps = new ArrayList<>();
        List<MrzFieldSpec> specs = format.fields();
        for (int i = 0; i < specs.size(); i++) {
            for (int j = i + 1; j < specs.size(); j++) {
                MrzFieldSpec a = specs.get(i);
                MrzFieldSpec b = specs.get(j);
                if (a.line() == b.line() && a.from() < b.to() && b.from() < a.to()) {
                    overlaps.add(a.name() + " 与 " + b.name() + " 区间重叠");
                }
            }
        }
        assertThat(overlaps).as("%s 字段区间重叠", format).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(MrzFormat.class)
    void checkDigitPositionsAreNotInsideDataFields(MrzFormat format) {
        // 校验位不能落在任何数据字段区间内，否则校验位本身会被当成数据参与计算
        List<String> conflicts = new ArrayList<>();
        for (MrzFieldSpec holder : format.fields()) {
            if (!holder.hasCheckDigit()) {
                continue;
            }
            for (MrzFieldSpec other : format.fields()) {
                if (other.line() == holder.checkLine()
                        && holder.checkPos() >= other.from() && holder.checkPos() < other.to()) {
                    conflicts.add(holder.name() + " 的校验位落在 " + other.name() + " 区间内");
                }
            }
        }
        assertThat(conflicts).as("%s 校验位与数据字段冲突", format).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(MrzFormat.class)
    void compositeSegmentsStayInsideLines(MrzFormat format) {
        MrzCompositeSpec composite = format.composite();
        List<String> violations = new ArrayList<>();
        for (MrzCompositeSpec.Segment seg : composite.segments()) {
            if (seg.line() < 0 || seg.line() >= format.lineCount()) {
                violations.add("综合校验段行下标 " + seg.line() + " 越界");
            }
            if (seg.from() < 0 || seg.to() > format.lineLength() || seg.from() >= seg.to()) {
                violations.add("综合校验段区间 [" + seg.from() + "," + seg.to() + ") 非法");
            }
        }
        if (composite.checkPos() < 0 || composite.checkPos() >= format.lineLength()) {
            violations.add("综合校验位下标越界");
        }
        assertThat(violations).as("%s 综合校验位规格非法", format).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(MrzFormat.class)
    void hasLinePatternForEveryLine(MrzFormat format) {
        for (int i = 0; i < format.lineCount(); i++) {
            assertThat(format.linePattern(i)).as("%s 第 %d 行缺少结构正则", format, i + 1).isNotNull();
        }
    }

    @ParameterizedTest
    @EnumSource(MrzFormat.class)
    void declaresTheFieldsEveryMachineReadableDocumentHas(MrzFormat format) {
        // 只有这三个字段是所有机读证件共有的。更早的版本还要求 sex/nationality/names，
        // 但实测中国出入境证件的单行机读码后发现那是过度约束——它的机读码只有
        // 证件号与两个日期，姓名性别国籍全在可视区。
        for (String required : List.of("documentType", "documentNumber", "birthDate", "expiryDate")) {
            assertThat(format.field(required)).as("%s 缺少字段 %s", format, required).isNotNull();
        }
    }

    @ParameterizedTest
    @EnumSource(value = MrzFormat.class, names = {"TD1", "TD2", "TD3"})
    void icaoFormatsCarryIdentityFieldsInTheMrz(MrzFormat format) {
        // ICAO 版式的机读区自带身份信息，可视区只是补充
        for (String required : List.of("issuingState", "sex", "nationality", "names")) {
            assertThat(format.hasField(required)).as("%s 缺少字段 %s", format, required).isTrue();
        }
        assertThat(format.nameLine()).isNotNegative();
    }

    @Test
    void chineseExitEntryFormatKeepsIdentityOutsideTheMrz() {
        // 中国出入境证件的机读码不含姓名/性别/国籍——解析器必须从可视区取，
        // 这条断言把该事实钉住，防止有人照搬 ICAO 的假设去写解析器
        MrzFormat f = MrzFormat.CN_EEP_9;
        assertThat(f.lineCount()).isEqualTo(1);
        assertThat(f.lineLength()).isEqualTo(30);
        assertThat(f.hasField("names")).isFalse();
        assertThat(f.hasField("sex")).isFalse();
        assertThat(f.hasField("nationality")).isFalse();
        assertThat(f.nameLine()).isEqualTo(-1);
        // 字段位置由公安部公开的证件样本实测确认
        assertThat(f.field("documentNumber").from()).isEqualTo(2);
        assertThat(f.field("documentNumber").to()).isEqualTo(11);
        assertThat(f.field("expiryDate").from()).isEqualTo(13);
        assertThat(f.field("birthDate").from()).isEqualTo(21);
        assertThat(f.composite().checkPos()).isEqualTo(29);
    }

    @Test
    void td3MatchesIcaoPassportLayout() {
        // TD3 是唯一有真实样图验证过的版式，这里把它的关键下标钉死，
        // 防止后续调整规格表时把已验证的护照路径改坏
        MrzFormat f = MrzFormat.TD3;
        assertThat(f.lineCount()).isEqualTo(2);
        assertThat(f.lineLength()).isEqualTo(44);
        assertThat(f.field("documentNumber").from()).isEqualTo(0);
        assertThat(f.field("documentNumber").to()).isEqualTo(9);
        assertThat(f.field("documentNumber").checkPos()).isEqualTo(9);
        assertThat(f.field("birthDate").from()).isEqualTo(13);
        assertThat(f.field("sex").from()).isEqualTo(20);
        assertThat(f.field("expiryDate").from()).isEqualTo(21);
        assertThat(f.field("optionalData").to()).isEqualTo(42);
        assertThat(f.composite().checkPos()).isEqualTo(43);
        assertThat(f.nameLine()).isZero();
    }

    @Test
    void td1SpansThreeLinesWithNamesLast() {
        MrzFormat f = MrzFormat.TD1;
        assertThat(f.lineCount()).isEqualTo(3);
        assertThat(f.lineLength()).isEqualTo(30);
        // TD1 与 TD2/TD3 最大的结构差异：证件号在第 1 行，姓名区独占第 3 行
        assertThat(f.field("documentNumber").line()).isZero();
        assertThat(f.nameLine()).isEqualTo(2);
        assertThat(f.field("names").line()).isEqualTo(2);
    }

    @Test
    void linesCarryingCheckDigitsAreSorted() {
        assertThat(MrzFormat.TD3.linesCarryingCheckDigits()).containsExactly(1);
        assertThat(MrzFormat.TD2.linesCarryingCheckDigits()).containsExactly(1);
        // TD1 的证件号校验位在第 1 行，其余在第 2 行
        assertThat(MrzFormat.TD1.linesCarryingCheckDigits()).containsExactly(0, 1);
    }

    @Test
    void unknownFieldNameFailsLoudly() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> MrzFormat.TD3.field("nosuchfield")))
                .hasMessageContaining("nosuchfield");
    }
}
