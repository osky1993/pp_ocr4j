package com.example.ppocr4j.parser.validate;

/**
 * 单个字段的校验结论。
 *
 * <p>校验<b>不修改</b>字段值，只给出判断——上游解析器抽出什么就是什么，
 * 这里只回答「这个值可不可信」。调用方可据此把 {@code valid=false} 的记录
 * 送进人工复核队列，而不是照单全收。
 *
 * @param valid 是否通过校验
 * @param rule  所用规则（如 {@code ISO 7064 MOD 11-2}），便于调用方理解判断依据
 * @param note  说明。校验失败时给出可操作的线索（如「含 GB 32100 排除的字母 I/O，
 *              疑似把 1/0 读错」），而不是笼统的「校验失败」
 */
public record FieldValidation(boolean valid, String rule, String note) {

    public static FieldValidation pass(String rule) {
        return new FieldValidation(true, rule, null);
    }

    public static FieldValidation fail(String rule, String note) {
        return new FieldValidation(false, rule, note);
    }
}
