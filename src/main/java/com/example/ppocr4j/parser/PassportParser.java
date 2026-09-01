package com.example.ppocr4j.parser;

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.LabelMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 护照 OCR 结构化解析器（ICAO 9303 TD3 版面）。
 *
 * <p><b>核心策略：MRZ 优先，可视区兜底。</b>护照资料页底部的两行 44 字符机读区（MRZ）
 * 是国际标准格式，字段定长定位且自带校验位，可靠性远高于逐字段标签定位——
 * 因此本解析器先解 MRZ，再用可视区（VIZ）补齐 MRZ 里没有的字段
 * （中文姓名、出生地点、签发地点、签发机关）。
 *
 * <p>MRZ TD3 第二行的定长布局（下标从 0 起）：
 * <pre>
 *   [0,9)   护照号码        [9]     护照号校验位
 *   [10,13) 国籍三字码      [13,19) 出生日期 YYMMDD   [19] 出生日期校验位
 *   [20]    性别 M/F/X      [21,27) 有效期 YYMMDD     [27] 有效期校验位
 *   [28,42) 个人号（中国护照放身份证号）              [42] 个人号校验位
 *   [43]    综合校验位
 * </pre>
 *
 * <p><b>已知 OCR 容错点</b>（均在真实样图上复现过）：
 * <ul>
 *   <li>MRZ 一行可能被检测框切成多段（尾部填充符 {@code <<<} 单独成框）——
 *       故按 y 聚类后按 x 拼接，而不是直接取单框；</li>
 *   <li>姓名区分隔符 {@code <<} 可能被少读成一个 {@code <}——
 *       此时不猜姓名边界，{@code surname}/{@code givenNames} 置 null，
 *       仅填 {@code nameEn}（见 {@link PassportResult#getSurname()} 说明）；</li>
 *   <li>校验位不匹配时字段照常返回，但 {@code mrzValid=false} 提示需人工复核。</li>
 * </ul>
 *
 * <p><b>为什么可视区不用 {@code LabelMatcher.matchValue*}</b>：该系列方法内部会跳过
 * 文本匹配 {@code [A-Za-z\s]+} 的候选框（为中文证件设计的去噪规则），
 * 而护照可视区大量字段是纯英文/拼音，会被整体过滤掉。故本类自写
 * {@link #valueRightOf} 做位置匹配，仅复用 {@code LabelMatcher} 的坐标工具与标签定位。
 *
 * <p>本解析器不依赖注入的推理引擎：识别由本项目的 {@code OcrService} 流水线完成，
 * 这里只消费 {@code parseResults(List)}，故 engine 传 null。
 */
@Component
public class PassportParser extends BaseStructuredParser<PassportResult> {

    private static final Logger log = LoggerFactory.getLogger(PassportParser.class);

    /** MRZ TD3 单行标准长度。 */
    private static final int MRZ_LINE_LEN = 44;
    /** MRZ 合法字符集：大写字母、数字、填充符。 */
    private static final Pattern MRZ_CHARSET = Pattern.compile("[A-Z0-9<]+");
    /**
     * MRZ 第二行结构（宽松版）：护照号(9) + 校验位 + 国籍(3) + 生日(6) + 校验位
     * + 性别 + 有效期(6) + 校验位，共 28 位；其后的个人号区允许缺失（OCR 截断）。
     */
    private static final Pattern MRZ_LINE2 = Pattern.compile(
            "^[A-Z0-9<]{9}[0-9<][A-Z<]{3}[0-9<]{6}[0-9<][MFX<][0-9<]{6}[0-9<].*$");
    /** MRZ 第一行结构：P + 次类型 + 签发国(3) + 姓名区。 */
    private static final Pattern MRZ_LINE1 = Pattern.compile("^P[A-Z<][A-Z<]{3}[A-Z<]+$");
    /** MRZ 日期：YYMMDD。 */
    private static final Pattern MRZ_DATE = Pattern.compile("\\d{6}");

    /**
     * 可视区日期：{@code 27 3月/MAR 2014}（中国护照）或 {@code 09 MAA/MAR 2014}（荷兰护照）。
     *
     * <p>OCR 会把框内空格吃掉，中国护照因而变成 {@code 273月/MAR2014}——「日」与「月」
     * 的数字直接粘连。故这里<b>不</b>直接用正则切「日」，而是先锚定可靠的
     * 「三字母月份缩写 + 四位年」，再回头从前导数字串里剥出「日」（见 {@link #vizDate}）。
     */
    private static final Pattern VIZ_DATE = Pattern.compile("^(\\d{1,4})?\\D{0,4}?([A-Z]{3})\\D{0,2}(\\d{4})");

    /**
     * 可视区已知标签关键词：值框若包含其中任一，说明抓到的其实是<b>另一个字段的标签</b>而非值。
     *
     * <p>护照资料页是双列排版，标签右侧往往紧邻另一列的标签
     * （如「性别/Sex」右侧就是「国籍/Nationality」），不做这层过滤会大面积串字段。
     */
    private static final List<String> VIZ_LABEL_WORDS = List.of(
            "类型", "国家码", "护照号码", "姓名", "性别", "国籍",
            "出生日期", "出生地点", "签发日期", "签发地点", "有效期", "签发机关",
            "持照人", "签名", "护照",
            "TYPE", "CODE", "PASSPORT", "NAME", "SEX", "NATIONAL",
            "DATE", "PLACE", "AUTHORITY", "SIGNATURE", "SURNAME", "GIVEN");

    /** 英文月份缩写 → 月份数字（MRZ/可视区通用，含荷兰语 MAA/MEI/OKT）。 */
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("JAN", 1), Map.entry("FEB", 2), Map.entry("MAR", 3), Map.entry("APR", 4),
            Map.entry("MAY", 5), Map.entry("JUN", 6), Map.entry("JUL", 7), Map.entry("AUG", 8),
            Map.entry("SEP", 9), Map.entry("OCT", 10), Map.entry("NOV", 11), Map.entry("DEC", 12),
            // 荷兰语缩写（样图为荷兰 SPECIMEN，中英以外的版面按需补充）
            Map.entry("MAA", 3), Map.entry("MEI", 5), Map.entry("OKT", 10));

    /**
     * 构造护照解析器。
     *
     * <p>engine 传 null：本项目的结构化链路只走 {@code parseResults(List)} 纯函数，
     * 识别由 {@code OcrService} 统一完成（档次选择、并发闸门、doc_ori 转正）。
     * 因此不要调用继承来的 {@code parse(byte[]/Path/...)} 一站式方法，会 NPE。
     */
    public PassportParser() {
        super(null);
    }

    @Override
    public PassportResult parseResults(List<PPOcrV6Result> results) {
        PassportResult r = new PassportResult();
        r.setRawResults(new ArrayList<>(results));
        if (results.isEmpty()) {
            return r;
        }
        // 1) MRZ 优先：定长解析 + 校验位自验
        MrzLines mrz = findMrz(results);
        if (mrz.line2 != null) {
            parseLine2(mrz, r);
        }
        if (mrz.line1 != null) {
            parseLine1(mrz, r);
        }
        // 2) 可视区兜底：补 MRZ 没有的字段，并在 MRZ 缺失时顶上
        parseViz(results, r);
        if (mrz.line1 == null && mrz.line2 == null) {
            log.warn("护照解析：未定位到 MRZ 机读区，全部字段退化为可视区标签定位");
        }
        return r;
    }

    // ==================================================================
    // MRZ 定位
    // ==================================================================

    /**
     * MRZ 两行的文本与来源框。
     *
     * @param line1     第一行清洗后文本；未找到为 null
     * @param line2     第二行清洗后文本；未找到为 null
     * @param line1Boxes 第一行来源 OCR 框（可能多段拼接）
     * @param line2Boxes 第二行来源 OCR 框
     */
    private record MrzLines(String line1, String line2,
                            List<PPOcrV6Result> line1Boxes, List<PPOcrV6Result> line2Boxes) {
        static MrzLines empty() {
            return new MrzLines(null, null, List.of(), List.of());
        }
    }

    /** 一行 MRZ 候选：按 y 聚类后拼接的文本 + 组成它的框。 */
    private record MrzRow(String text, List<PPOcrV6Result> boxes, int centerY) {}

    /**
     * 在 OCR 结果中定位 MRZ 两行。
     *
     * <p>步骤：筛出 MRZ 风格候选框（清洗后只含 {@code [A-Z0-9<]} 且含填充符 {@code <}，
     * 正常版面文字几乎不可能满足）→ 按 y 聚类成行 → 行内按 x 拼接 →
     * 用结构正则挑出第二行（信息密度最高）与第一行（{@code P} 开头且在第二行上方）。
     */
    private static MrzLines findMrz(List<PPOcrV6Result> results) {
        List<PPOcrV6Result> candidates = new ArrayList<>();
        for (PPOcrV6Result r : results) {
            String t = clean(r.text());
            // 含 < 是 MRZ 的强特征：可视区正常文本不会出现填充符
            if (!t.isEmpty() && t.indexOf('<') >= 0 && MRZ_CHARSET.matcher(t).matches()) {
                candidates.add(r);
            }
        }
        if (candidates.isEmpty()) {
            return MrzLines.empty();
        }
        List<MrzRow> rows = groupIntoRows(candidates);

        // 第二行：优先取结构完整（匹配 TD3 正则）且最长的一行
        MrzRow line2 = rows.stream()
                .filter(row -> MRZ_LINE2.matcher(row.text()).matches())
                .max(Comparator.comparingInt(row -> row.text().length()))
                .orElse(null);
        // 第一行：P 开头、结构匹配，且必须在第二行上方（y 更小）
        final int line2Y = line2 == null ? Integer.MAX_VALUE : line2.centerY();
        MrzRow line1 = rows.stream()
                .filter(row -> row != line2)
                .filter(row -> row.centerY() < line2Y)
                .filter(row -> MRZ_LINE1.matcher(row.text()).matches())
                .max(Comparator.comparingInt(row -> row.text().length()))
                .orElse(null);

        return new MrzLines(
                line1 == null ? null : line1.text(),
                line2 == null ? null : line2.text(),
                line1 == null ? List.of() : line1.boxes(),
                line2 == null ? List.of() : line2.boxes());
    }

    /**
     * 把候选框按 y 聚类成行，行内按 x 升序拼接。
     *
     * <p>处理 OCR 把一行 MRZ 切成多段的场景（真实样图上尾部填充符 {@code <<<}
     * 就被单独切成一个框）。同行判定：两框 y 中心差小于两者平均高度的一半。
     */
    private static List<MrzRow> groupIntoRows(List<PPOcrV6Result> candidates) {
        List<PPOcrV6Result> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingInt(PassportParser::centerY));

        List<List<PPOcrV6Result>> groups = new ArrayList<>();
        for (PPOcrV6Result r : sorted) {
            List<PPOcrV6Result> target = null;
            for (List<PPOcrV6Result> g : groups) {
                PPOcrV6Result head = g.get(0);
                int tolerance = (height(head) + height(r)) / 4;
                if (Math.abs(centerY(head) - centerY(r)) <= Math.max(tolerance, 1)) {
                    target = g;
                    break;
                }
            }
            if (target == null) {
                target = new ArrayList<>();
                groups.add(target);
            }
            target.add(r);
        }

        List<MrzRow> rows = new ArrayList<>(groups.size());
        for (List<PPOcrV6Result> g : groups) {
            g.sort(Comparator.comparingInt(LabelMatcher::minX));
            StringBuilder sb = new StringBuilder();
            int ySum = 0;
            for (PPOcrV6Result r : g) {
                sb.append(clean(r.text()));
                ySum += centerY(r);
            }
            rows.add(new MrzRow(sb.toString(), g, ySum / g.size()));
        }
        return rows;
    }

    /**
     * MRZ 文本清洗：去空格、统一大写、把常见的 OCR 误识别字符还原为填充符。
     *
     * <p>只做保守替换（书名号/全角尖括号 → {@code <}），不做 O↔0、I↔1 这类
     * 高风险猜测——那会把校验位算错，反而掩盖了识别质量问题。
     */
    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toUpperCase(Locale.ROOT)
                .replace("«", "<<")
                .replace("‹", "<")
                .replace("＜", "<")
                .replaceAll("[\\s\\u00a0]", "");
    }

    // ==================================================================
    // MRZ 解析
    // ==================================================================

    /**
     * 解析 MRZ 第二行：护照号、国籍、出生日期、性别、有效期、个人号，并校验 5 个校验位。
     *
     * <p>长度不足 44（OCR 截断）时按已有前缀尽力解析，缺失段留 null，
     * 并把 {@code mrzValid} 置 false。
     */
    private void parseLine2(MrzLines mrz, PassportResult r) {
        String l2 = mrz.line2();
        r.setMrzLine2(l2);
        applyBoxes(r, "mrzLine2", mrz.line2Boxes());

        r.setPassportNo(strip(sub(l2, 0, 9)));
        r.setNationality(strip(sub(l2, 10, 13)));
        r.setBirthDate(mrzDate(sub(l2, 13, 19), true));
        String sex = sub(l2, 20, 21);
        r.setSex(sex == null || "<".equals(sex) ? null : sex);
        r.setExpiryDate(mrzDate(sub(l2, 21, 27), false));
        r.setPersonalNumber(strip(sub(l2, 28, 42)));

        // 护照号/国籍/日期等字段来自第二行，box 指向同一批框，便于前端整体高亮
        for (String field : List.of("passportNo", "nationality", "birthDate", "sex", "expiryDate", "personalNumber")) {
            applyBoxes(r, field, mrz.line2Boxes());
        }

        r.setMrzValid(validate(l2));
    }

    /**
     * 校验 MRZ 第二行的 5 个校验位（护照号 / 出生日期 / 有效期 / 个人号 / 综合）。
     *
     * @return 全部通过返回 true；长度不足或任一位不匹配返回 false
     */
    private static boolean validate(String l2) {
        if (l2.length() < MRZ_LINE_LEN) {
            log.warn("护照解析：MRZ 第二行长度 {} < {}，跳过校验位验证", l2.length(), MRZ_LINE_LEN);
            return false;
        }
        boolean ok = checkDigit(l2.substring(0, 9), l2.charAt(9), "护照号")
                & checkDigit(l2.substring(13, 19), l2.charAt(19), "出生日期")
                & checkDigit(l2.substring(21, 27), l2.charAt(27), "有效期")
                & checkDigit(l2.substring(28, 42), l2.charAt(42), "个人号")
                & checkDigit(l2.substring(0, 10) + l2.substring(13, 20) + l2.substring(21, 43),
                             l2.charAt(43), "综合");
        if (ok) {
            log.debug("护照解析：MRZ 校验位全部通过");
        }
        return ok;
    }

    /**
     * 单个校验位比对。
     *
     * @param data     被校验的数据段
     * @param expected MRZ 中印刷的校验位字符
     * @param name     字段名（日志用）
     * @return 匹配返回 true
     */
    private static boolean checkDigit(String data, char expected, String name) {
        int actual = computeCheckDigit(data);
        if (expected == '<' || !Character.isDigit(expected) || actual != expected - '0') {
            log.warn("护照解析：MRZ {} 校验位不匹配（印刷 '{}'，算得 {}），字段仍返回但需人工复核",
                    name, expected, actual);
            return false;
        }
        return true;
    }

    /**
     * ICAO 9303 校验位算法：字符权值（数字取本身，A-Z 取 10~35，{@code <} 取 0）
     * 按 7-3-1 循环加权求和后取模 10。
     */
    private static int computeCheckDigit(String data) {
        int[] weights = {7, 3, 1};
        int sum = 0;
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            int v;
            if (c == '<') {
                v = 0;
            } else if (c >= '0' && c <= '9') {
                v = c - '0';
            } else if (c >= 'A' && c <= 'Z') {
                v = c - 'A' + 10;
            } else {
                v = 0;
            }
            sum += v * weights[i % 3];
        }
        return sum % 10;
    }

    /**
     * 解析 MRZ 第一行：证件类型、签发国、姓名区。
     *
     * <p>姓名区规则见 {@link PassportResult#getSurname()}——{@code <<} 缺失时
     * 只填 {@code nameEn}，不猜姓/名边界。
     */
    private void parseLine1(MrzLines mrz, PassportResult r) {
        String l1 = mrz.line1();
        r.setMrzLine1(l1);
        applyBoxes(r, "mrzLine1", mrz.line1Boxes());

        r.setDocumentType(strip(sub(l1, 0, 1)));
        r.setIssuingCountry(strip(sub(l1, 2, 5)));
        applyBoxes(r, "documentType", mrz.line1Boxes());
        applyBoxes(r, "issuingCountry", mrz.line1Boxes());

        if (l1.length() <= 5) {
            return;
        }
        // 去掉尾部填充符后的姓名区
        String names = l1.substring(5).replaceAll("<+$", "");
        if (names.isEmpty()) {
            return;
        }
        r.setNameEn(names.replaceAll("<+", " ").trim());
        applyBoxes(r, "nameEn", mrz.line1Boxes());

        int sep = names.indexOf("<<");
        if (sep < 0) {
            log.warn("护照解析：MRZ 姓名区未出现分隔符 \"<<\"（原文 \"{}\"），"
                    + "姓/名不做切分以免猜错，请使用 nameEn 或可视区字段", names);
            return;
        }
        String surname = names.substring(0, sep).replaceAll("<+", " ").trim();
        String given = names.substring(sep + 2).replaceAll("<+", " ").trim();
        r.setSurname(surname.isEmpty() ? null : surname);
        r.setGivenNames(given.isEmpty() ? null : given);
        applyBoxes(r, "surname", mrz.line1Boxes());
        applyBoxes(r, "givenNames", mrz.line1Boxes());
    }

    /**
     * MRZ 日期 YYMMDD → {@code yyyy-MM-dd}。
     *
     * <p>两位年份的世纪推断：
     * <ul>
     *   <li>出生日期不可能在未来 → {@code 20YY} 超过今年则取 {@code 19YY}；</li>
     *   <li>有效期一律取 {@code 20YY}（MRZ 护照 1980 年代才出现，2000 年前的早已失效）。</li>
     * </ul>
     *
     * @param yymmdd MRZ 原文 6 位；null 或格式非法返回 null
     * @param birth  true=出生日期，false=有效期
     */
    private static String mrzDate(String yymmdd, boolean birth) {
        if (yymmdd == null || !MRZ_DATE.matcher(yymmdd).matches()) {
            return null;
        }
        int yy = Integer.parseInt(yymmdd.substring(0, 2));
        int mm = Integer.parseInt(yymmdd.substring(2, 4));
        int dd = Integer.parseInt(yymmdd.substring(4, 6));
        if (mm < 1 || mm > 12 || dd < 1 || dd > 31) {
            log.warn("护照解析：MRZ 日期 \"{}\" 月/日越界，置 null", yymmdd);
            return null;
        }
        int year = 2000 + yy;
        if (birth && year > Year.now().getValue()) {
            year = 1900 + yy;
        }
        return String.format("%04d-%02d-%02d", year, mm, dd);
    }

    // ==================================================================
    // 可视区（VIZ）解析
    // ==================================================================

    /**
     * 可视区解析：补齐 MRZ 里没有的字段（中文姓名、出生地点、签发地点、签发日期、签发机关），
     * 并在 MRZ 缺失时兜底护照号码等主字段。
     *
     * <p>标签用「中文/English」双语版面的中文前缀定位（{@code findLabelBox} 支持前缀匹配，
     * 能命中 OCR 出的「出生地点/Placeofbirth」整框）。
     */
    private void parseViz(List<PPOcrV6Result> results, PassportResult r) {
        // 只在 MRZ 没给出时兜底的字段
        if (r.getPassportNo() == null) {
            setViz(results, r, "护照号码", "PassportNo", "passportNo", v -> v, r::setPassportNo);
        }
        if (r.getSex() == null) {
            setViz(results, r, "性别", "Sex", "sex", PassportParser::normalizeSex, r::setSex);
        }
        if (r.getBirthDate() == null) {
            setViz(results, r, "出生日期", "Dateofbirth", "birthDate", PassportParser::vizDate, r::setBirthDate);
        }
        if (r.getExpiryDate() == null) {
            setViz(results, r, "有效期至", "Dateofexpiry", "expiryDate", PassportParser::vizDate, r::setExpiryDate);
        }
        // MRZ 中不存在、只能从可视区取的字段
        setViz(results, r, "姓名", null, "nameCn", PassportParser::chineseOnly, r::setNameCn);
        setViz(results, r, "出生地点", "Placeofbirth", "placeOfBirth", v -> v, r::setPlaceOfBirth);
        setViz(results, r, "签发地点", "Placeofissue", "placeOfIssue", v -> v, r::setPlaceOfIssue);
        setViz(results, r, "签发日期", "Dateofissue", "issueDate", PassportParser::vizDate, r::setIssueDate);
        setViz(results, r, "签发机关", "Authority", "authority", v -> v, r::setAuthority);
    }

    /**
     * 按标签取可视区字段值，归一化后写入结果并回填 fieldBoxes。
     *
     * <p>归一化返回 null（如 {@code nameCn} 抓到的框里没有中文）时，字段与 fieldBoxes
     * 都不写——避免出现「字段为空但坐标已登记」的不一致状态。
     *
     * @param label      中文标签（如 "签发机关"）
     * @param altLabel   中文标签未命中时的英文备选标签；无则传 null
     * @param fieldName  字段名（fieldBoxes 的 key）
     * @param normalizer 值归一化函数，返回 null 表示该值不可用
     * @param setter     字段写入回调
     */
    private void setViz(List<PPOcrV6Result> results, PassportResult r, String label, String altLabel,
                        String fieldName, java.util.function.UnaryOperator<String> normalizer,
                        java.util.function.Consumer<String> setter) {
        PPOcrV6Result labelBox = LabelMatcher.findLabelBox(results, label);
        if (labelBox == null && altLabel != null) {
            labelBox = LabelMatcher.findLabelBox(results, altLabel);
        }
        if (labelBox == null) {
            return;
        }
        // 护照资料页是「标签在上、值在下」的排版（与行驶证的左右布局相反），故向下优先
        PPOcrV6Result value = valueBelow(results, labelBox);
        if (value == null) {
            value = valueRightOf(results, labelBox);
        }
        if (value == null) {
            log.debug("护照解析：可视区标签 \"{}\" 未匹配到值框", label);
            return;
        }
        String normalized = normalizer.apply(value.text().trim());
        if (normalized == null || normalized.isEmpty()) {
            return;
        }
        setter.accept(normalized);
        applyBoxes(r, fieldName, List.of(value));
    }

    /**
     * 取标签框右侧、y 范围重叠的最靠左候选框。
     *
     * <p>与 {@code LabelMatcher.matchValueByCenterWithBox} 的差别：<b>不排除纯英文候选框</b>。
     * 护照可视区大量字段是纯拉丁字母（如 "GUANGDONG"、"CHN"），上游那条为中文证件设计的
     * 去噪规则会把它们全部滤掉。
     */
    private static PPOcrV6Result valueRightOf(List<PPOcrV6Result> results, PPOcrV6Result labelBox) {
        int labelCenterX = (LabelMatcher.minX(labelBox) + LabelMatcher.maxX(labelBox)) / 2;
        int labelMinY = LabelMatcher.minY(labelBox);
        int labelMaxY = LabelMatcher.maxY(labelBox);
        PPOcrV6Result best = null;
        int bestX = Integer.MAX_VALUE;
        for (PPOcrV6Result r : results) {
            if (r == labelBox || isLabelLike(r.text())) {
                continue;
            }
            int x0 = LabelMatcher.minX(r);
            if ((x0 + LabelMatcher.maxX(r)) / 2 <= labelCenterX) {
                continue;
            }
            if (LabelMatcher.maxY(r) < labelMinY || LabelMatcher.minY(r) > labelMaxY) {
                continue;
            }
            if (x0 < bestX) {
                bestX = x0;
                best = r;
            }
        }
        return best;
    }

    /**
     * 取标签框正下方一行内、x 有重叠的最近候选框。
     *
     * <p>护照资料页多为「标签在上、值在下」的排版（如「签发机关/Authority」下方是
     * 「公安部出入境管理局」），与行驶证的左右布局不同，因此需要这条向下兜底。
     */
    private static PPOcrV6Result valueBelow(List<PPOcrV6Result> results, PPOcrV6Result labelBox) {
        int labelMinX = LabelMatcher.minX(labelBox);
        int labelMaxX = LabelMatcher.maxX(labelBox);
        int labelBottom = LabelMatcher.maxY(labelBox);
        int maxGap = Math.max(height(labelBox) * 3, 1);
        PPOcrV6Result best = null;
        int bestY = Integer.MAX_VALUE;
        for (PPOcrV6Result r : results) {
            if (r == labelBox || isLabelLike(r.text())) {
                continue;
            }
            int top = LabelMatcher.minY(r);
            if (top < labelBottom || top - labelBottom > maxGap) {
                continue;
            }
            // x 需与标签有重叠，避免抓到同一行右侧其他字段的值
            if (LabelMatcher.maxX(r) < labelMinX || LabelMatcher.minX(r) > labelMaxX) {
                continue;
            }
            if (top < bestY) {
                bestY = top;
                best = r;
            }
        }
        return best;
    }

    /**
     * 判断一个 OCR 框是否是「字段标签」而非字段值。
     *
     * <p>空文本、或含 {@link #VIZ_LABEL_WORDS} 任一关键词的文本都视为标签。
     * 双列排版下不做这层过滤，取值会大量串到隔壁字段的标签上。
     */
    private static boolean isLabelLike(String text) {
        if (text == null || text.trim().isEmpty()) {
            return true;
        }
        String t = text.toUpperCase(Locale.ROOT);
        for (String word : VIZ_LABEL_WORDS) {
            if (t.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 可视区日期 → {@code yyyy-MM-dd}。
     *
     * <p>护照可视区日期是「日 + 月份缩写 + 年」的国际格式，用月份缩写而非纯数字解析，
     * 避免 日/月 顺序歧义。难点在于 OCR 会吃掉框内空格：
     * <pre>
     *   中国  "27 3月/MAR 2014" → "273月/MAR2014"   日与月粘连成 "273"
     *   荷兰  "09 MAA/MAR 2014" → "09MAA/MAR2014"   前导数字就是日
     * </pre>
     * 因此先锚定可靠的「月份缩写 + 四位年」，再从前导数字串里剥「日」：
     * 若该数字串以月份数字结尾且不止一位，说明月份数字被粘了进来，去掉后余下即为日。
     *
     * @return 规范化日期；无法解析时返回原文（保留信息优于丢弃）
     */
    private static String vizDate(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = VIZ_DATE.matcher(raw.toUpperCase(Locale.ROOT).replaceAll("[\\s\\u00a0]", ""));
        if (!m.find()) {
            return raw;
        }
        Integer month = MONTHS.get(m.group(2));
        String digits = m.group(1);
        if (month == null || digits == null || digits.isEmpty()) {
            return raw;
        }
        // 剥离粘连的月份数字："273"(27日3月) → "27"，"1012"(10日12月) → "10"
        String monthDigits = String.valueOf(month);
        if (digits.length() > monthDigits.length() && digits.endsWith(monthDigits)) {
            digits = digits.substring(0, digits.length() - monthDigits.length());
        }
        int day;
        try {
            day = Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return raw;
        }
        if (day < 1 || day > 31) {
            log.warn("护照解析：可视区日期 \"{}\" 解析出的日 {} 越界，返回原文", raw, day);
            return raw;
        }
        return String.format("%s-%02d-%02d", m.group(3), month, day);
    }

    /**
     * 性别归一化：可视区形如「男/M」「女/F」「V/F」，统一成 MRZ 的 M/F。
     *
     * @return M / F；无法判定时返回原文
     */
    private static String normalizeSex(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.toUpperCase(Locale.ROOT);
        if (t.contains("男") || t.endsWith("/M") || "M".equals(t)) {
            return "M";
        }
        if (t.contains("女") || t.endsWith("/F") || "F".equals(t)) {
            return "F";
        }
        return raw;
    }

    /**
     * 从「中文姓名/PINYIN」混排文本中取中文部分。
     *
     * <p>本字段专存中文姓名，因此不含中文时返回 null 而非原文——非中文版面的护照
     * 没有这个字段，抓到的多半是邻框噪声，宁可留空也不要填错值（拼音全名见 {@code nameEn}）。
     */
    private static String chineseOnly(String raw) {
        if (raw == null) {
            return null;
        }
        String cn = raw.replaceAll("[^\\u4e00-\\u9fa5·]", "").trim();
        return cn.isEmpty() ? null : cn;
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /** 安全子串：越界返回 null，供定长 MRZ 解析在 OCR 截断时降级。 */
    private static String sub(String s, int from, int to) {
        return (s == null || s.length() < to) ? null : s.substring(from, to);
    }

    /** 去掉 MRZ 填充符；结果为空返回 null。 */
    private static String strip(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replace("<", "").trim();
        return t.isEmpty() ? null : t;
    }

    /** 把一批来源框登记到结果的 fieldBoxes（供前端高亮）。 */
    private static void applyBoxes(PassportResult r, String field, List<PPOcrV6Result> boxes) {
        LabelMatcher.applyFieldBox(r, field, LabelMatcher.LabeledMatch.of("", boxes));
    }

    private static int centerY(PPOcrV6Result r) {
        return (LabelMatcher.minY(r) + LabelMatcher.maxY(r)) / 2;
    }

    private static int height(PPOcrV6Result r) {
        return LabelMatcher.maxY(r) - LabelMatcher.minY(r);
    }
}
