package com.example.ppocr4j.parser.mrz;

import java.util.List;

/**
 * 综合校验位规格：覆盖多个不连续数据段的那一位校验位。
 *
 * <p>它是 MRZ 里最有价值的一位——单字段校验位只能发现该字段内部的错字，
 * 综合校验位则把证件号、出生日期、有效期、可选数据全部串起来校验，
 * 能发现「两个字段各自自洽但整体被调换/篡改」的情况。
 *
 * @param segments  参与计算的数据段（按顺序拼接）
 * @param checkLine 校验位所在行下标
 * @param checkPos  校验位下标
 */
public record MrzCompositeSpec(List<Segment> segments, int checkLine, int checkPos) {

    /**
     * 参与综合校验的一个数据段。
     *
     * @param line 行下标（0 起）
     * @param from 起始下标（含）
     * @param to   结束下标（不含）
     */
    public record Segment(int line, int from, int to) {
    }

    /** 便捷构造：段以 {@code line,from,to} 三元组扁平给出。 */
    public static MrzCompositeSpec of(int checkLine, int checkPos, int... lineFromTo) {
        if (lineFromTo.length % 3 != 0) {
            throw new IllegalArgumentException("段参数必须是 line,from,to 的三元组");
        }
        List<Segment> segs = new java.util.ArrayList<>(lineFromTo.length / 3);
        for (int i = 0; i < lineFromTo.length; i += 3) {
            segs.add(new Segment(lineFromTo[i], lineFromTo[i + 1], lineFromTo[i + 2]));
        }
        return new MrzCompositeSpec(List.copyOf(segs), checkLine, checkPos);
    }
}
