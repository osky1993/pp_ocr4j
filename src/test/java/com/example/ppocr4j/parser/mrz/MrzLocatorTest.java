package com.example.ppocr4j.parser.mrz;

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 机读区定位测试：聚焦「OCR 把 MRZ 读残了会怎样」。
 *
 * <p>这些失真模式都是真实样图上复现过的，不是想象出来的边界情况。
 */
class MrzLocatorTest {

    private static PPOcrV6Result box(String text, int x0, int y0, int x1, int y1) {
        return new PPOcrV6Result(text, 0.99f, new int[][]{{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}}, 0);
    }

    private static final String L1 = "P<NLDDE<BRUIJN<<WILLEKE<LISELOTTE<<<<<<<<<<<";
    private static final String L2 = "SPECI20142NLD6503101F2403096999999990<<<<<84";

    @Test
    void locatesBothLinesInReadingOrder() {
        MrzDocument doc = MrzLocator.locate(
                List.of(box(L1, 100, 900, 900, 950), box(L2, 100, 960, 900, 1010)),
                MrzFormat.TD3);

        assertThat(doc).isNotNull();
        assertThat(doc.format()).isEqualTo(MrzFormat.TD3);
        assertThat(doc.line(0)).isEqualTo(L1);
        assertThat(doc.line(1)).isEqualTo(L2);
    }

    @Test
    void joinsRowSplitAcrossBoxesByXOrder() {
        // 真实样图上尾部填充符被单独切成一个检测框
        MrzDocument doc = MrzLocator.locate(
                List.of(box("P<NLDDE<BRUIJN<<WILLEKE<LISELOTTE<<<", 100, 900, 800, 950),
                        box("<<<<<<<<", 810, 902, 900, 948),
                        box(L2, 100, 960, 900, 1010)),
                MrzFormat.TD3);

        assertThat(doc).isNotNull();
        assertThat(doc.line(0)).isEqualTo(L1);
        assertThat(doc.boxes(0)).hasSize(2);
    }

    @Test
    void reassemblesOutOfOrderBoxes() {
        // 检测框的顺序不保证，定位必须靠坐标而不是列表顺序
        MrzDocument doc = MrzLocator.locate(
                List.of(box(L2, 100, 960, 900, 1010), box(L1, 100, 900, 900, 950)),
                MrzFormat.TD3);

        assertThat(doc.line(0)).isEqualTo(L1);
        assertThat(doc.line(1)).isEqualTo(L2);
    }

    @Test
    void findsDataLineEvenWhenNameLineMissing() {
        // 姓名行漏检时，数据行仍应可用——大部分关键字段都在数据行上
        MrzDocument doc = MrzLocator.locate(List.of(box(L2, 100, 960, 900, 1010)), MrzFormat.TD3);

        assertThat(doc).isNotNull();
        assertThat(doc.line(0)).isNull();
        assertThat(doc.line(1)).isEqualTo(L2);
        assertThat(doc.hasAnyLine()).isTrue();
    }

    @Test
    void returnsNullWhenNoDataLine() {
        // 只有姓名行、没有数据行：锚定行认不出 → 整体判定为未定位
        assertThat(MrzLocator.locate(List.of(box(L1, 100, 900, 900, 950)), MrzFormat.TD3)).isNull();
    }

    @Test
    void ignoresOrdinaryPageText() {
        assertThat(MrzLocator.locate(
                List.of(box("中华人民共和国", 0, 0, 100, 20),
                        box("PASSPORT", 0, 30, 100, 50),
                        box("广东/GUANGDONG", 0, 60, 100, 80)),
                MrzFormat.TD3))
                .as("可视区文本不含填充符，不应被认作 MRZ")
                .isNull();
    }

    @Test
    void normalizesLookalikeCharactersWhileLocating() {
        // OCR 可能把填充符读成书名号或全角尖括号
        String noisy = L1.replace("<<", "«");
        MrzDocument doc = MrzLocator.locate(
                List.of(box(noisy, 100, 900, 900, 950), box(L2, 100, 960, 900, 1010)),
                MrzFormat.TD3);

        assertThat(doc).isNotNull();
        assertThat(doc.line(0)).isEqualTo(L1);
    }

    @Test
    void restrictingAllowedFormatsPreventsMisdetection() {
        // 只允许 TD1 时，一份 TD3 护照机读区不应被强行解读
        assertThat(MrzLocator.locate(
                List.of(box(L1, 100, 900, 900, 950), box(L2, 100, 960, 900, 1010)),
                MrzFormat.TD1))
                .isNull();
    }

    @Test
    void fieldAccessorsReadThroughTheFormatTable() {
        MrzDocument doc = MrzLocator.locate(
                List.of(box(L1, 100, 900, 900, 950), box(L2, 100, 960, 900, 1010)),
                MrzFormat.TD3);

        assertThat(doc.field("documentNumber")).isEqualTo("SPECI2014");
        assertThat(doc.field("nationality")).isEqualTo("NLD");
        assertThat(doc.field("birthDate")).isEqualTo("650310");
        assertThat(doc.field("sex")).isEqualTo("F");
        assertThat(doc.field("expiryDate")).isEqualTo("240309");
        assertThat(doc.field("documentType")).isEqualTo("P");
        assertThat(doc.field("issuingState")).isEqualTo("NLD");
        assertThat(doc.charAt(1, 43)).isEqualTo('4');
    }

    @Test
    void safeAccessorsReturnNullInsteadOfThrowing() {
        MrzDocument doc = MrzLocator.locate(List.of(box(L2, 100, 960, 900, 1010)), MrzFormat.TD3);

        // 姓名行缺失：取该行任何字段都应安全返回 null，而不是抛异常
        assertThat(doc.field("documentType")).isNull();
        assertThat(doc.sub(0, 0, 5)).isNull();
        assertThat(doc.charAt(0, 0)).isNull();
        assertThat(doc.sub(1, 0, 999)).as("越界子串").isNull();
        assertThat(doc.boxes(99)).isEmpty();
    }
}
