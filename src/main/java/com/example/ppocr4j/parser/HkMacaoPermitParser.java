package com.example.ppocr4j.parser;

import com.example.ppocr4j.parser.mrz.MrzCheck;
import com.example.ppocr4j.parser.mrz.MrzDates;
import com.example.ppocr4j.parser.mrz.MrzDocument;
import com.example.ppocr4j.parser.mrz.MrzFormat;
import com.example.ppocr4j.parser.mrz.MrzLocator;
import com.example.ppocr4j.parser.mrz.MrzText;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.LabelMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 往来港澳通行证 OCR 结构化解析器（卡式电子证，正面资料面）。
 *
 * <p><b>策略：机读码优先，可视区补齐。</b>卡片正面底部有一行 30 字符机读码
 * （{@link MrzFormat#CN_EEP_9}），带四个校验位，可靠性最高；但它<b>只含</b>证件号、
 * 出生日期、有效期截止日——姓名、性别、签发机关、有效期起始日都得从可视区取。
 *
 * <p><b>可视区为什么以内容特征为主、标签定位为辅</b>（与 {@code PassportParser} 相反）：
 * 实测发现这张卡的标签 OCR 质量不稳定（tiny 档把「签发机关」读成「签发机美」、
 * 「有效期限」读成「有效期限门」，标签定位直接失效），但<b>值的特征极强</b>——
 * 证件号有固定前缀、日期是 {@code yyyy.MM.dd}、签发机关必含「管理局」。
 * 因此这里优先按值的形态匹配，标签只用于性别这类没有形态特征的字段。
 *
 * <p><b>两个实测踩到的 OCR 行为</b>：
 * <ul>
 *   <li>拼音姓名会被切成两个框（{@code ZHENGJIAN,} + {@code YANGBEN}），需按行拼接；</li>
 *   <li>签发机关与签发地点被合并进同一个框，中间的竖线分隔符被读成数字 {@code 1}
 *       （{@code 中华人民共和国出入境管理局1广东}），需要按「局」字边界拆分。</li>
 * </ul>
 *
 * <p>本解析器不依赖注入的推理引擎，识别由 {@code OcrService} 流水线完成。
 */
@Component
public class HkMacaoPermitParser extends BaseStructuredParser<HkMacaoPermitResult> {

    private static final Logger log = LoggerFactory.getLogger(HkMacaoPermitParser.class);

    /** 日志前缀，便于在共享的 MRZ 组件日志里分辨是哪种证件。 */
    private static final String LOG_PREFIX = "港澳通行证解析";

    /**
     * 证件号：{@code C} + 8 位数字（2018-12-03 前），或 {@code C} + 字母 + 7 位数字（之后，
     * 字母不含 I、O 以避免与 1、0 混淆）。规则出自国家移民管理局《出入境证件简明手册》。
     */
    private static final Pattern PERMIT_NO = Pattern.compile("C(?:\\d{8}|[A-HJ-NP-Z]\\d{7})");
    /** 可视区日期：{@code 1981.08.03}。 */
    private static final Pattern VIZ_DATE = Pattern.compile("(\\d{4})\\.(\\d{2})\\.(\\d{2})");
    /** 可视区有效期区间：{@code 2019.01.18 - 2029.01.17}。 */
    private static final Pattern VIZ_DATE_RANGE = Pattern.compile(
            "(\\d{4}\\.\\d{2}\\.\\d{2})\\s*[-—~至]\\s*(\\d{4}\\.\\d{2}\\.\\d{2})");
    /** 拼音姓名：全大写，含逗号分隔的姓与名。 */
    private static final Pattern NAME_EN = Pattern.compile("[A-Z]{2,}\\s*,[A-Z\\s.]*");
    /** 中文姓名：2~6 个汉字（含少数民族姓名的间隔号）。 */
    private static final Pattern NAME_CN = Pattern.compile("[\\u4e00-\\u9fa5·]{2,6}");
    /** 签发机关：以「局」结尾的机构名，后面可能粘着签发地点。 */
    private static final Pattern AUTHORITY = Pattern.compile("([\\u4e00-\\u9fa5]{4,}局)[\\s1|｜/]*([\\u4e00-\\u9fa5]{0,10})");

    /**
     * 中文姓名的排除项：版面上的固定文字，只在「拼音姓名没读到、退回全版面扫描」时才用。
     *
     * <p>注意<b>不包含</b>「证件样本」——官方样本卡上那四个字就印在姓名位置上，
     * 真实证件上这个位置是持证人姓名。把它排除掉会让样本卡的 nameCn 永远为空，
     * 反而测不到这条路径。
     */
    private static final List<String> NAME_CN_EXCLUDE = List.of(
            "往来港澳通行证", "出生日期", "性别", "有效期限", "签发机关", "签发地点",
            "中华人民共和国", "出入境管理局", "男", "女");

    /**
     * 构造解析器。engine 传 null：识别由 {@code OcrService} 统一完成，
     * 这里只消费 {@code parseResults(List)}。
     */
    public HkMacaoPermitParser() {
        super(null);
    }

    @Override
    public HkMacaoPermitResult parseResults(List<PPOcrV6Result> results) {
        HkMacaoPermitResult r = new HkMacaoPermitResult();
        r.setRawResults(new ArrayList<>(results));
        if (results.isEmpty()) {
            return r;
        }

        // 1) 机读码优先：四个校验位自验
        MrzDocument mrz = MrzLocator.locate(results, MrzFormat.CN_EEP_9);
        if (mrz != null && mrz.hasAnyLine()) {
            parseMrz(mrz, r);
        } else {
            log.warn("{}：未定位到机读码，全部字段退化为可视区识别", LOG_PREFIX);
        }

        // 2) 可视区：补机读码没有的字段，并在机读码缺失时顶上
        parseViz(results, r);
        crossCheckExpiry(r);
        return r;
    }

    // ==================================================================
    // 机读码
    // ==================================================================

    /** 按 {@link MrzFormat#CN_EEP_9} 规格取字段并校验四个校验位。 */
    private void parseMrz(MrzDocument mrz, HkMacaoPermitResult r) {
        List<PPOcrV6Result> boxes = mrz.boxes(0);
        r.setMrzLine(mrz.line(0));
        applyBoxes(r, "mrzLine", boxes);

        r.setDocumentType(MrzText.strip(mrz.field("documentType")));
        r.setPermitNo(MrzText.strip(mrz.field("documentNumber")));
        r.setBirthDate(MrzDates.parse(mrz.field("birthDate"), true, LOG_PREFIX));
        r.setExpiryDate(MrzDates.parse(mrz.field("expiryDate"), false, LOG_PREFIX));
        for (String field : List.of("documentType", "permitNo", "birthDate", "expiryDate")) {
            applyBoxes(r, field, boxes);
        }

        r.setMrzValid(MrzCheck.validateAll(mrz, LOG_PREFIX));
    }

    // ==================================================================
    // 可视区
    // ==================================================================

    /**
     * 可视区解析。除性别外一律按值的形态匹配——这张卡的标签 OCR 质量不稳定，
     * 但值的特征足够强，形态匹配比标签定位可靠得多。
     */
    private void parseViz(List<PPOcrV6Result> results, HkMacaoPermitResult r) {
        // 有效期区间：可视区独有的起始日，以及可与机读码交叉验证的截止日
        for (PPOcrV6Result box : results) {
            Matcher m = VIZ_DATE_RANGE.matcher(box.text().replace(" ", ""));
            if (m.find()) {
                r.setValidFrom(dotToDash(m.group(1)));
                if (r.getExpiryDate() == null) {
                    r.setExpiryDate(dotToDash(m.group(2)));
                    applyBoxes(r, "expiryDate", List.of(box));
                }
                applyBoxes(r, "validFrom", List.of(box));
                break;
            }
        }

        // 证件号：机读码缺失时从可视区右上角的印刷号补
        if (r.getPermitNo() == null) {
            findFirst(results, box -> {
                Matcher m = PERMIT_NO.matcher(box.text().replace(" ", "").toUpperCase(Locale.ROOT));
                return m.matches() ? m.group() : null;
            }, (value, box) -> {
                r.setPermitNo(value);
                applyBoxes(r, "permitNo", List.of(box));
            });
        }

        // 出生日期：机读码缺失时取版面上唯一的单个 yyyy.MM.dd（区间日期已在上面被吃掉）
        if (r.getBirthDate() == null) {
            findFirst(results, box -> {
                String t = box.text().replace(" ", "");
                if (VIZ_DATE_RANGE.matcher(t).find()) {
                    return null;
                }
                Matcher m = VIZ_DATE.matcher(t);
                return m.matches() ? dotToDash(m.group()) : null;
            }, (value, box) -> {
                r.setBirthDate(value);
                applyBoxes(r, "birthDate", List.of(box));
            });
        }

        parseSex(results, r);
        parseNames(results, r);
        parseAuthority(results, r);
    }

    /**
     * 性别：唯一没有形态特征的字段（就一个「男」或「女」字），因此用标签定位。
     * 版面是「标签在上、值在下」。
     */
    private void parseSex(List<PPOcrV6Result> results, HkMacaoPermitResult r) {
        PPOcrV6Result label = LabelMatcher.findLabelBox(results, "性别");
        if (label != null) {
            PPOcrV6Result value = valueBelow(results, label);
            if (value != null) {
                String sex = normalizeSex(value.text());
                if (sex != null) {
                    r.setSex(sex);
                    applyBoxes(r, "sex", List.of(value));
                    return;
                }
            }
        }
        // 标签没读准时（tiny 档常见）退回全版面找单字「男」/「女」
        findFirst(results, box -> {
            String t = box.text().trim();
            return ("男".equals(t) || "女".equals(t)) ? normalizeSex(t) : null;
        }, (value, box) -> {
            r.setSex(value);
            applyBoxes(r, "sex", List.of(box));
        });
    }

    /**
     * 姓名：中文姓名与拼音姓名都在照片右侧且<b>没有标签</b>，只能按形态区分。
     *
     * <p>拼音姓名可能被 OCR 切成多个框（{@code ZHENGJIAN,} + {@code YANGBEN}），
     * 因此先把同一行的大写英文框按 x 顺序拼接再匹配。
     */
    private void parseNames(List<PPOcrV6Result> results, HkMacaoPermitResult r) {
        // 拼音姓名：同行拼接后取含逗号的那一行
        List<PPOcrV6Result> nameEnBoxes = List.of();
        for (Line line : groupRows(results, box -> box.text().matches("[A-Z][A-Z,.\\s]*"))) {
            Matcher m = NAME_EN.matcher(line.text());
            if (m.find()) {
                r.setNameEn(tidyNameEn(m.group()));
                applyBoxes(r, "nameEn", line.boxes());
                nameEnBoxes = line.boxes();
                break;
            }
        }

        // 中文姓名：版面上它就在拼音姓名的正上方。用这个位置约束而不是全版面扫描——
        // 实测 tiny 档会把「签发机关」读成「签发机美」，四个汉字、不在排除表里，
        // 全版面扫描会把它当成姓名填进来（假阳性比留空危险得多）。
        PPOcrV6Result hit = nameEnBoxes.isEmpty() ? null : chineseAbove(results, nameEnBoxes);
        if (hit == null) {
            // 拼音姓名没读到时的兜底：全版面扫描 + 排除版面固定文字
            hit = results.stream()
                    .filter(box -> NAME_CN.matcher(box.text().trim()).matches())
                    .filter(box -> NAME_CN_EXCLUDE.stream().noneMatch(box.text()::contains))
                    .min(Comparator.comparingInt(LabelMatcher::minY))
                    .orElse(null);
        }
        if (hit != null) {
            r.setNameCn(hit.text().trim());
            applyBoxes(r, "nameCn", List.of(hit));
        }
    }

    /**
     * 在拼音姓名上方、x 范围重叠处找中文姓名框。
     *
     * @param anchorBoxes 拼音姓名的来源框（可能多个）
     * @return 最贴近上方的纯中文短串框；没有则返回 null
     */
    private static PPOcrV6Result chineseAbove(List<PPOcrV6Result> results, List<PPOcrV6Result> anchorBoxes) {
        int anchorTop = anchorBoxes.stream().mapToInt(LabelMatcher::minY).min().orElse(0);
        int anchorMinX = anchorBoxes.stream().mapToInt(LabelMatcher::minX).min().orElse(0);
        int anchorMaxX = anchorBoxes.stream().mapToInt(LabelMatcher::maxX).max().orElse(0);
        int maxGap = anchorBoxes.stream().mapToInt(HkMacaoPermitParser::height).max().orElse(1) * 3;

        PPOcrV6Result best = null;
        int bestBottom = Integer.MIN_VALUE;
        for (PPOcrV6Result box : results) {
            String t = box.text().trim();
            if (!NAME_CN.matcher(t).matches()) {
                continue;
            }
            int bottom = LabelMatcher.maxY(box);
            if (bottom > anchorTop || anchorTop - bottom > maxGap) {
                continue;
            }
            if (LabelMatcher.maxX(box) < anchorMinX || LabelMatcher.minX(box) > anchorMaxX) {
                continue;
            }
            if (bottom > bestBottom) {
                bestBottom = bottom;
                best = box;
            }
        }
        return best;
    }

    /**
     * 签发机关与签发地点。
     *
     * <p>实测这两个值会被 OCR 合并进同一个框，中间的竖线分隔符被读成数字 {@code 1}
     * （{@code 中华人民共和国出入境管理局1广东}）。以「局」字为边界拆分。
     */
    private void parseAuthority(List<PPOcrV6Result> results, HkMacaoPermitResult r) {
        for (PPOcrV6Result box : results) {
            Matcher m = AUTHORITY.matcher(box.text().trim());
            if (!m.find()) {
                continue;
            }
            r.setIssuingAuthority(m.group(1));
            applyBoxes(r, "issuingAuthority", List.of(box));
            String place = m.group(2);
            if (place != null && !place.isBlank()) {
                r.setPlaceOfIssue(place);
                applyBoxes(r, "placeOfIssue", List.of(box));
            }
            return;
        }
        log.debug("{}：未匹配到签发机关", LOG_PREFIX);
    }

    /**
     * 机读码与可视区的有效期截止日应当一致。不一致说明至少有一边读错了，
     * 记 warn 提示人工复核——两个独立来源互相印证是这张卡少有的自校验机会。
     */
    private void crossCheckExpiry(HkMacaoPermitResult r) {
        if (r.getMrzLine() == null || r.getValidFrom() == null || r.getExpiryDate() == null) {
            return;
        }
        if (r.getValidFrom().compareTo(r.getExpiryDate()) >= 0) {
            log.warn("{}：有效期起始日 {} 不早于截止日 {}，请人工复核",
                    LOG_PREFIX, r.getValidFrom(), r.getExpiryDate());
        }
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /** 同一行的若干文本框及其拼接文本。 */
    private record Line(String text, List<PPOcrV6Result> boxes) {
    }

    /**
     * 把满足条件的框按 y 聚类成行、行内按 x 拼接。
     *
     * <p>用于还原被 OCR 切碎的一行文字（如拼音姓名）。同行判定与
     * {@code MrzLocator} 一致：y 中心差不超过两者平均高度的一半。
     */
    private static List<Line> groupRows(List<PPOcrV6Result> results,
                                        java.util.function.Predicate<PPOcrV6Result> filter) {
        List<PPOcrV6Result> picked = new ArrayList<>();
        for (PPOcrV6Result box : results) {
            if (!box.text().isBlank() && filter.test(box)) {
                picked.add(box);
            }
        }
        picked.sort(Comparator.comparingInt(HkMacaoPermitParser::centerY));

        List<List<PPOcrV6Result>> groups = new ArrayList<>();
        for (PPOcrV6Result box : picked) {
            List<PPOcrV6Result> target = null;
            for (List<PPOcrV6Result> g : groups) {
                PPOcrV6Result head = g.get(0);
                int tolerance = Math.max((height(head) + height(box)) / 4, 1);
                if (Math.abs(centerY(head) - centerY(box)) <= tolerance) {
                    target = g;
                    break;
                }
            }
            if (target == null) {
                target = new ArrayList<>();
                groups.add(target);
            }
            target.add(box);
        }

        List<Line> lines = new ArrayList<>(groups.size());
        for (List<PPOcrV6Result> g : groups) {
            g.sort(Comparator.comparingInt(LabelMatcher::minX));
            StringBuilder sb = new StringBuilder();
            for (PPOcrV6Result box : g) {
                sb.append(box.text().trim());
            }
            lines.add(new Line(sb.toString(), g));
        }
        return lines;
    }

    /** 扫描所有框，把第一个提取成功的值交给回调。 */
    private static void findFirst(List<PPOcrV6Result> results,
                                  java.util.function.Function<PPOcrV6Result, String> extractor,
                                  java.util.function.BiConsumer<String, PPOcrV6Result> sink) {
        for (PPOcrV6Result box : results) {
            String value = extractor.apply(box);
            if (value != null && !value.isBlank()) {
                sink.accept(value, box);
                return;
            }
        }
    }

    /** 取标签框正下方、x 有重叠的最近候选框（版面是「标签在上、值在下」）。 */
    private static PPOcrV6Result valueBelow(List<PPOcrV6Result> results, PPOcrV6Result labelBox) {
        int labelMinX = LabelMatcher.minX(labelBox);
        int labelMaxX = LabelMatcher.maxX(labelBox);
        int labelBottom = LabelMatcher.maxY(labelBox);
        int maxGap = Math.max(height(labelBox) * 3, 1);
        PPOcrV6Result best = null;
        int bestY = Integer.MAX_VALUE;
        for (PPOcrV6Result box : results) {
            if (box == labelBox || box.text().isBlank()) {
                continue;
            }
            int top = LabelMatcher.minY(box);
            if (top < labelBottom || top - labelBottom > maxGap) {
                continue;
            }
            if (LabelMatcher.maxX(box) < labelMinX || LabelMatcher.minX(box) > labelMaxX) {
                continue;
            }
            if (top < bestY) {
                bestY = top;
                best = box;
            }
        }
        return best;
    }

    /** {@code 1981.08.03} → {@code 1981-08-03}。 */
    private static String dotToDash(String date) {
        return date == null ? null : date.replace('.', '-');
    }

    /** 「男」→ M、「女」→ F；无法判定返回 null（不猜）。 */
    private static String normalizeSex(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.contains("男")) {
            return "M";
        }
        if (t.contains("女")) {
            return "F";
        }
        return null;
    }

    /** 规整拼音姓名：逗号后补一个空格、压掉多余空白。 */
    private static String tidyNameEn(String raw) {
        return raw.replace(",", ", ").replaceAll("\\s+", " ").trim();
    }

    /** 把一批来源框登记到 fieldBoxes（供前端高亮）。 */
    private static void applyBoxes(HkMacaoPermitResult r, String field, List<PPOcrV6Result> boxes) {
        LabelMatcher.applyFieldBox(r, field, LabelMatcher.LabeledMatch.of("", boxes));
    }

    private static int centerY(PPOcrV6Result r) {
        return (LabelMatcher.minY(r) + LabelMatcher.maxY(r)) / 2;
    }

    private static int height(PPOcrV6Result r) {
        return LabelMatcher.maxY(r) - LabelMatcher.minY(r);
    }
}
