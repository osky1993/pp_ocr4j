package com.example.ppocr4j.parser.mrz;

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.LabelMatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 在 OCR 结果中定位机读区。
 *
 * <p>三步：筛出 MRZ 风格候选框 → 按 y 聚类成行、行内按 x 拼接 → 用版式的行结构正则
 * 认出各行。第二步是必需的：真实样图上 OCR 会把一行 MRZ 切成多段
 * （尾部填充符 {@code <<<} 单独成一个检测框）。
 *
 * <p><b>调用方必须声明允许的版式</b>（{@link #locate(List, Set)} 的 {@code allowed} 参数）。
 * 已知证件类型的解析器应当只传自己的版式——放开自动检测会引入「本该按 TD3 解析的护照
 * 被误判成 TD2」这类风险，而调用方明明知道答案。
 */
public final class MrzLocator {

    private MrzLocator() {
    }

    /** 一行 MRZ 候选：按 y 聚类后拼接的文本 + 组成它的框 + 行中心 y。 */
    private record Row(String text, List<PPOcrV6Result> boxes, int centerY) {
    }

    /**
     * 定位机读区。
     *
     * @param results OCR 结果
     * @param allowed 允许的版式集合；多于一个时按「认出的行数」择优
     * @return 定位结果；一行都没认出时返回 null
     */
    public static MrzDocument locate(List<PPOcrV6Result> results, Set<MrzFormat> allowed) {
        List<PPOcrV6Result> candidates = new ArrayList<>();
        for (PPOcrV6Result r : results) {
            String t = MrzText.clean(r.text());
            // 含 < 是 MRZ 的强特征：可视区正常文本不会出现填充符
            if (!t.isEmpty() && t.indexOf('<') >= 0 && MrzFormat.CHARSET.matcher(t).matches()) {
                candidates.add(r);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        List<Row> rows = groupIntoRows(candidates);

        MrzDocument best = null;
        int bestScore = 0;
        for (MrzFormat format : allowed) {
            MrzDocument doc = match(rows, format);
            if (doc == null) {
                continue;
            }
            int score = (int) doc.lines().stream().filter(java.util.Objects::nonNull).count();
            if (score > bestScore) {
                bestScore = score;
                best = doc;
            }
        }
        return best;
    }

    /**
     * 用一种版式去匹配已聚类的行。
     *
     * <p>先锚定信息密度最高的那一行（{@link MrzFormat#anchorLine()}），再按各行相对锚定行
     * 的上下位置寻找其余行。用位置约束而不是纯正则，可以避免把版面上其他含 {@code <}
     * 的文本认成 MRZ 行。
     *
     * @return 匹配结果；锚定行都认不出时返回 null
     */
    private static MrzDocument match(List<Row> rows, MrzFormat format) {
        int anchorIdx = format.anchorLine();
        Row anchor = rows.stream()
                .filter(row -> format.linePattern(anchorIdx).matcher(row.text()).matches())
                .max(Comparator.comparingInt(row -> row.text().length()))
                .orElse(null);
        if (anchor == null) {
            return null;
        }

        Row[] picked = new Row[format.lineCount()];
        picked[anchorIdx] = anchor;
        for (int i = 0; i < format.lineCount(); i++) {
            if (i == anchorIdx) {
                continue;
            }
            final int lineIdx = i;
            final boolean above = i < anchorIdx;
            final Row anchorRow = anchor;
            picked[i] = rows.stream()
                    .filter(row -> row != anchorRow)
                    .filter(row -> above ? row.centerY() < anchorRow.centerY() : row.centerY() > anchorRow.centerY())
                    .filter(row -> format.linePattern(lineIdx).matcher(row.text()).matches())
                    .max(Comparator.comparingInt(row -> row.text().length()))
                    .orElse(null);
        }

        List<String> lines = new ArrayList<>(format.lineCount());
        List<List<PPOcrV6Result>> boxes = new ArrayList<>(format.lineCount());
        for (Row row : picked) {
            lines.add(row == null ? null : row.text());
            boxes.add(row == null ? List.of() : row.boxes());
        }
        return new MrzDocument(format, lines, boxes);
    }

    /**
     * 把候选框按 y 聚类成行，行内按 x 升序拼接。
     *
     * <p>同行判定：两框 y 中心差不超过两者平均高度的一半。
     */
    private static List<Row> groupIntoRows(List<PPOcrV6Result> candidates) {
        List<PPOcrV6Result> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingInt(MrzLocator::centerY));

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

        List<Row> rows = new ArrayList<>(groups.size());
        for (List<PPOcrV6Result> g : groups) {
            g.sort(Comparator.comparingInt(LabelMatcher::minX));
            StringBuilder sb = new StringBuilder();
            int ySum = 0;
            for (PPOcrV6Result r : g) {
                sb.append(MrzText.clean(r.text()));
                ySum += centerY(r);
            }
            rows.add(new Row(sb.toString(), g, ySum / g.size()));
        }
        return rows;
    }

    /** 便捷重载：只允许一种版式。 */
    public static MrzDocument locate(List<PPOcrV6Result> results, MrzFormat format) {
        return locate(results, Set.of(format));
    }

    /** 便捷重载：允许多种版式，按认出的行数择优。 */
    public static MrzDocument locate(List<PPOcrV6Result> results, MrzFormat... formats) {
        return locate(results, new java.util.LinkedHashSet<>(Arrays.asList(formats)));
    }

    private static int centerY(PPOcrV6Result r) {
        return (LabelMatcher.minY(r) + LabelMatcher.maxY(r)) / 2;
    }

    private static int height(PPOcrV6Result r) {
        return LabelMatcher.maxY(r) - LabelMatcher.minY(r);
    }
}
