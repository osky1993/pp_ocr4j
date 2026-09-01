package com.example.ppocr4j.parser.mrz;

import java.util.List;
import java.util.regex.Pattern;

/**
 * ICAO 9303 机读区版式（TD1 / TD2 / TD3）。
 *
 * <p><b>设计要点：版式差异是数据，不是代码。</b>三种版式的行数、行长、字段位置、
 * 校验位位置全部声明在这个枚举里，定位与校验逻辑只有一份、按数据表驱动。
 * 支持一种新版式 = 往这里加一个枚举常量，不新增任何解析分支。
 *
 * <p>三种版式的实际用途：
 * <ul>
 *   <li><b>TD3</b>（2 行 × 44）——护照本，{@code PassportParser} 使用；</li>
 *   <li><b>TD1</b>（3 行 × 30）——卡式证件与居留许可。中国的往来港澳/台湾通行证
 *       按 ICAO DOC 9303 TD-1 标准制作（85.6×54mm）；</li>
 *   <li><b>TD2</b>（2 行 × 36）——部分护照卡与旅行证件。</li>
 * </ul>
 *
 * <p><b>字段位置表的权威来源是 ICAO Doc 9303 Part 4/5/6。</b>各版式的字段名沿用
 * 规范用语（{@code documentNumber}/{@code optionalData}），由各证件解析器映射到
 * 自己的业务字段名（护照的 {@code passportNo} 等）。
 */
public enum MrzFormat {

    /**
     * TD1：3 行 × 30 字符。卡式证件与居留许可。
     *
     * <pre>
     * 行1: [0,2)类型 [2,5)签发国 [5,14)证件号 [14]cd [15,30)可选数据1
     * 行2: [0,6)出生 [6]cd [7]性别 [8,14)有效期 [14]cd [15,18)国籍 [18,29)可选数据2 [29]综合cd
     * 行3: [0,30)姓名区
     * </pre>
     */
    TD1(3, 30, 1,
            List.of(
                    MrzFieldSpec.of("documentType", "证件类型", 0, 0, 2),
                    MrzFieldSpec.of("issuingState", "签发国", 0, 2, 5),
                    MrzFieldSpec.checked("documentNumber", "证件号", 0, 5, 14, 14),
                    MrzFieldSpec.of("optionalData1", "可选数据1", 0, 15, 30),
                    MrzFieldSpec.checked("birthDate", "出生日期", 1, 0, 6, 6),
                    MrzFieldSpec.of("sex", "性别", 1, 7, 8),
                    MrzFieldSpec.checked("expiryDate", "有效期", 1, 8, 14, 14),
                    MrzFieldSpec.of("nationality", "国籍", 1, 15, 18),
                    MrzFieldSpec.of("optionalData2", "可选数据2", 1, 18, 29),
                    MrzFieldSpec.of("names", "姓名区", 2, 0, 30)),
            MrzCompositeSpec.of(1, 29, 0, 5, 30, 1, 0, 7, 1, 8, 15, 1, 18, 29),
            List.of(
                    Pattern.compile("^[A-Z<]{2}[A-Z<]{3}[A-Z0-9<]{9}[0-9<][A-Z0-9<]{15}$"),
                    Pattern.compile("^[0-9<]{6}[0-9<][MFX<][0-9<]{6}[0-9<][A-Z<]{3}[A-Z0-9<]{11}[0-9<]$"),
                    Pattern.compile("^[A-Z<]+$")),
            2),

    /**
     * TD2：2 行 × 36 字符。护照卡与部分旅行证件。
     *
     * <pre>
     * 行1: [0,1)类型 [2,5)签发国 [5,36)姓名区
     * 行2: [0,9)证件号 [9]cd [10,13)国籍 [13,19)出生 [19]cd [20]性别
     *      [21,27)有效期 [27]cd [28,35)可选数据 [35]综合cd
     * </pre>
     */
    TD2(2, 36, 1,
            List.of(
                    MrzFieldSpec.of("documentType", "证件类型", 0, 0, 1),
                    MrzFieldSpec.of("issuingState", "签发国", 0, 2, 5),
                    MrzFieldSpec.of("names", "姓名区", 0, 5, 36),
                    MrzFieldSpec.checked("documentNumber", "证件号", 1, 0, 9, 9),
                    MrzFieldSpec.of("nationality", "国籍", 1, 10, 13),
                    MrzFieldSpec.checked("birthDate", "出生日期", 1, 13, 19, 19),
                    MrzFieldSpec.of("sex", "性别", 1, 20, 21),
                    MrzFieldSpec.checked("expiryDate", "有效期", 1, 21, 27, 27),
                    MrzFieldSpec.of("optionalData", "可选数据", 1, 28, 35)),
            MrzCompositeSpec.of(1, 35, 1, 0, 10, 1, 13, 20, 1, 21, 35),
            List.of(
                    Pattern.compile("^[A-Z<][A-Z<][A-Z<]{3}[A-Z<]+$"),
                    Pattern.compile("^[A-Z0-9<]{9}[0-9<][A-Z<]{3}[0-9<]{6}[0-9<][MFX<][0-9<]{6}[0-9<].*$")),
            0),

    /**
     * TD3：2 行 × 44 字符。护照本。
     *
     * <pre>
     * 行1: [0,1)类型 [2,5)签发国 [5,44)姓名区
     * 行2: [0,9)证件号 [9]cd [10,13)国籍 [13,19)出生 [19]cd [20]性别
     *      [21,27)有效期 [27]cd [28,42)可选数据 [42]cd [43]综合cd
     * </pre>
     *
     * <p>注意行1的正则是 {@code ^P...}——TD3 目前只用于护照，{@code P} 是护照的证件类型码。
     * 这与重构前 {@code PassportParser.MRZ_LINE1} 完全一致。
     */
    TD3(2, 44, 1,
            List.of(
                    MrzFieldSpec.of("documentType", "证件类型", 0, 0, 1),
                    MrzFieldSpec.of("issuingState", "签发国", 0, 2, 5),
                    MrzFieldSpec.of("names", "姓名区", 0, 5, 44),
                    MrzFieldSpec.checked("documentNumber", "护照号", 1, 0, 9, 9),
                    MrzFieldSpec.of("nationality", "国籍", 1, 10, 13),
                    MrzFieldSpec.checked("birthDate", "出生日期", 1, 13, 19, 19),
                    MrzFieldSpec.of("sex", "性别", 1, 20, 21),
                    MrzFieldSpec.checked("expiryDate", "有效期", 1, 21, 27, 27),
                    MrzFieldSpec.checked("optionalData", "个人号", 1, 28, 42, 42)),
            MrzCompositeSpec.of(1, 43, 1, 0, 10, 1, 13, 20, 1, 21, 43),
            List.of(
                    Pattern.compile("^P[A-Z<][A-Z<]{3}[A-Z<]+$"),
                    Pattern.compile("^[A-Z0-9<]{9}[0-9<][A-Z<]{3}[0-9<]{6}[0-9<][MFX<][0-9<]{6}[0-9<].*$")),
            0),

    /**
     * 中国出入境证件单行机读码（1 行 × 30 字符，证件号 9 位）。
     *
     * <p><b>这不是 ICAO 版式</b>，是中国出入境证件的自有格式，印在卡片正面底部
     * （不是背面——背面是签注区）。虽然卡片按 ICAO DOC 9303 TD-1 的物理尺寸
     * （85.6×54mm）制作，但机读码布局与 TD1 的 3 行 × 30 完全不同。
     *
     * <pre>
     * [0,2)  证件标识（往来港澳通行证为 "CS"）
     * [2,11) 证件号 9 位   [11] 校验位
     * [12]   分隔 '<'
     * [13,19) 有效期至 YYMMDD  [19] 校验位
     * [20]   分隔 '<'
     * [21,27) 出生日期 YYMMDD  [27] 校验位
     * [28]   分隔 '<'
     * [29]   综合校验位 = cd([2,12) + [13,20) + [21,28))
     * </pre>
     *
     * <p><b>校验位算法与 ICAO 完全相同</b>（7-3-1 加权模 10），综合校验位的构成方式
     * （数据段连同各自校验位一起拼接）也一致。本规格由公安部出入境管理局公开的
     * 往来港澳通行证证件样本实测确认，四个校验位全部自洽，且证件号、出生日期、
     * 有效期三项与卡片正面印刷值逐一吻合。
     *
     * <p>注意机读码<b>不含姓名、性别、国籍</b>——这些只在可视区，需要标签定位补齐。
     *
     * <p><b>适用范围</b>：已验证往来港澳通行证（号码 {@code C}+8 位，或 2018-12-03 后
     * {@code C}+字母+7 位）。往来台湾通行证号码为 {@code L}/{@code T}+8 位，同为 9 位、
     * 同一发证体系，疑似同版式但<b>未经样图验证</b>。台湾居民来往大陆通行证（台胞证）
     * 号码为 8 位、机读码标识为 {@code CT}，字段位置因号码长度不同而<b>必然不同</b>，
     * 需要另立版式常量，取得样图前不要用本常量解析它。
     */
    CN_EEP_9(1, 30, 0,
            List.of(
                    MrzFieldSpec.of("documentType", "证件标识", 0, 0, 2),
                    MrzFieldSpec.checked("documentNumber", "证件号", 0, 2, 11, 11),
                    MrzFieldSpec.checked("expiryDate", "有效期", 0, 13, 19, 19),
                    MrzFieldSpec.checked("birthDate", "出生日期", 0, 21, 27, 27)),
            MrzCompositeSpec.of(0, 29, 0, 2, 12, 0, 13, 20, 0, 21, 28),
            List.of(Pattern.compile("^[A-Z0-9<]{2}[A-Z0-9<]{9}[0-9<]<[0-9<]{6}[0-9<]<[0-9<]{6}[0-9<]<[0-9<]$")),
            -1);

    /** MRZ 合法字符集：大写字母、数字、填充符。 */
    public static final Pattern CHARSET = Pattern.compile("[A-Z0-9<]+");

    private final int lineCount;
    private final int lineLength;
    private final int anchorLine;
    private final List<MrzFieldSpec> fields;
    private final MrzCompositeSpec composite;
    private final List<Pattern> linePatterns;
    private final int nameLine;

    MrzFormat(int lineCount, int lineLength, int anchorLine, List<MrzFieldSpec> fields,
              MrzCompositeSpec composite, List<Pattern> linePatterns, int nameLine) {
        this.lineCount = lineCount;
        this.lineLength = lineLength;
        this.anchorLine = anchorLine;
        this.fields = fields;
        this.composite = composite;
        this.linePatterns = linePatterns;
        this.nameLine = nameLine;
    }

    public int lineCount() {
        return lineCount;
    }

    public int lineLength() {
        return lineLength;
    }

    /**
     * 定位锚定行：信息密度最高、结构最容易辨认的那一行（三种版式都是第 2 行，
     * 因为它含证件号/日期/性别与多个校验位）。其余行相对它的上下位置来找。
     */
    public int anchorLine() {
        return anchorLine;
    }

    /**
     * 姓名区所在行（TD1 在第 3 行，TD2/TD3 在第 1 行）。
     *
     * @return 行下标；{@code -1} 表示该版式的机读码不含姓名区
     *         （中国出入境证件就是如此，姓名只在可视区）
     */
    public int nameLine() {
        return nameLine;
    }

    /**
     * 该版式是否声明了某个字段。
     *
     * <p>不同版式携带的字段并不相同：ICAO 版式含姓名/性别/国籍，
     * 而中国出入境证件的单行机读码只有证件号与两个日期。解析器取可选字段前
     * 应当先问一句，而不是假定字段一定存在。
     */
    public boolean hasField(String name) {
        for (MrzFieldSpec spec : fields) {
            if (spec.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    public List<MrzFieldSpec> fields() {
        return fields;
    }

    public MrzCompositeSpec composite() {
        return composite;
    }

    /** 第 {@code line} 行的结构正则，用于从候选文本框里辨认出这一行。 */
    public Pattern linePattern(int line) {
        return linePatterns.get(line);
    }

    /** 按字段名取规格。 */
    public MrzFieldSpec field(String name) {
        for (MrzFieldSpec spec : fields) {
            if (spec.name().equals(name)) {
                return spec;
            }
        }
        throw new IllegalArgumentException(this + " 没有字段 " + name);
    }

    /** 承载校验位的行下标（这些行必须完整，否则校验位下标会越界）。 */
    public int[] linesCarryingCheckDigits() {
        java.util.TreeSet<Integer> lines = new java.util.TreeSet<>();
        for (MrzFieldSpec spec : fields) {
            if (spec.hasCheckDigit()) {
                lines.add(spec.checkLine());
            }
        }
        lines.add(composite.checkLine());
        return lines.stream().mapToInt(Integer::intValue).toArray();
    }
}
