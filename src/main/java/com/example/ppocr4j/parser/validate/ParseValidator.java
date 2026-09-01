package com.example.ppocr4j.parser.validate;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化字段的后处理校验。
 *
 * <p><b>为什么是后处理而不是重写解析器</b>：上游 mica-ppocr-structured 的解析器在 jar 里，
 * 改不了；重写它们（{@code InvoiceParser} 34KB、{@code BusinessLicenseParser} 37KB）
 * 意味着接管上游的维护责任，升级时也享受不到上游修复。后处理只看解析出来的
 * 字段 Map，与解析器完全解耦，升级 mica-ppocr 时冲突最小。
 *
 * <p><b>这个方案的已知代价</b>：后处理只能<b>发现</b>问题，无法修复在上游产生的错值。
 * 最典型的是驾驶证号——上游正则 {@code \d{15,18}} 不接受末位 X，末位为 X 的证号
 * 会被兜底逻辑截断成错误的 17 位数字返回。后处理能把它标成 {@code valid=false}
 * 并在 note 里点明「疑似末位 X 被截断」，但拿不回那个 X。
 *
 * <p>校验结果以 {@code validations} 字段随响应返回，<b>不改动 {@code fields} 里的值</b>。
 */
@Component
public class ParseValidator {

    /**
     * 各 docType 下需要按身份证规则校验的字段。驾驶证号在中国就是身份证号。
     *
     * <p>护照的 {@code personalNumber} <b>不在这里</b>——只有中国护照的 MRZ 可选数据区
     * 放公民身份号码，其他国家含义各异（荷兰护照放的就是本国个人号）。
     * 无条件按身份证校验会对所有外国护照产生误报，见 {@link #validate}。
     */
    private static final Map<String, List<String>> ID_NUMBER_FIELDS = Map.of(
            "id-card", List.of("idNumber"),
            "driver-license", List.of("licenseNumber"));

    /** 需要按统一社会信用代码规则校验的字段。发票的买卖方税号通常也是信用代码。 */
    private static final Map<String, List<String>> CREDIT_CODE_FIELDS = Map.of(
            "business-license", List.of("creditCode"),
            "invoice", List.of("buyerTaxNo", "sellerTaxNo"));

    /** 需要按 Luhn 校验的字段。 */
    private static final Map<String, List<String>> LUHN_FIELDS = Map.of(
            "bank-card", List.of("cardNumber"));

    /**
     * 对一份解析结果做字段校验。
     *
     * @param docType 规范化后的证件类型
     * @param fields  解析出的业务字段
     * @return 字段名 → 校验结论；没有可校验字段时返回空 Map（不是 null）
     */
    public Map<String, FieldValidation> validate(String docType, Map<String, Object> fields) {
        Map<String, FieldValidation> out = new LinkedHashMap<>();
        if (fields == null || fields.isEmpty()) {
            return out;
        }

        for (String field : ID_NUMBER_FIELDS.getOrDefault(docType, List.of())) {
            text(fields, field).ifPresent(v -> out.put(field, validateIdNumber(v)));
        }
        for (String field : CREDIT_CODE_FIELDS.getOrDefault(docType, List.of())) {
            text(fields, field).ifPresent(v -> out.put(field, validateCreditCode(v)));
        }
        for (String field : LUHN_FIELDS.getOrDefault(docType, List.of())) {
            text(fields, field).ifPresent(v -> out.put(field, validateBankCard(v)));
        }
        if ("invoice".equals(docType)) {
            validateInvoiceAmounts(fields, out);
        }
        // 中国护照的 MRZ 可选数据区放的是公民身份号码，可以按身份证规则校验；
        // 其他签发国的这个字段含义完全不同，校验它只会产生误报——
        // 误报会让调用方逐渐忽略整个 validations，比不校验更有害
        if ("passport".equals(docType) && "CHN".equals(text(fields, "issuingCountry").orElse(null))) {
            text(fields, "personalNumber").ifPresent(v -> out.put("personalNumber", validateIdNumber(v)));
        }
        return out;
    }

    // ==================================================================
    // 各字段的校验规则
    // ==================================================================

    /**
     * 身份证号校验：校验位 + 出生日期段合法性。
     *
     * <p>特别识别「末位 X 被截断」这一上游已知缺陷：驾驶证解析器的正则是纯数字的
     * {@code \d{15,18}}，遇到末位为 X 的证号（约 1/11 的概率）会返回前 17 位数字。
     * 这种值长度恰好 17，是个很强的信号。
     */
    private static FieldValidation validateIdNumber(String value) {
        String rule = "ISO 7064 MOD 11-2（GB 11643）";
        if (value.length() == 17 && value.chars().allMatch(Character::isDigit)) {
            return FieldValidation.fail(rule,
                    "长度为 17 位纯数字，疑似末位校验码 X 被上游正则截断（上游 DriverLicenseParser "
                            + "的 \\d{15,18} 不接受 X）。请核对证件原件末位是否为 X");
        }
        if (value.length() == 15) {
            return FieldValidation.fail(rule, "15 位一代身份证号，无校验位可验，需人工核对");
        }
        if (value.length() != 18) {
            return FieldValidation.fail(rule, "长度为 " + value.length() + " 位，应为 18 位");
        }
        if (!CheckDigits.isValidIdNumber(value)) {
            return FieldValidation.fail(rule, "校验位不匹配，疑似 OCR 误识（如 0↔8、1↔7）");
        }
        if (!CheckDigits.hasValidBirthSegment(value)) {
            return FieldValidation.fail(rule, "校验位通过但出生日期段（第 7~14 位）不是合法日期");
        }
        return FieldValidation.pass(rule);
    }

    /**
     * 统一社会信用代码校验。
     *
     * <p>先单独判断有没有出现 GB 32100 排除的 I/O/S/V/Z——这五个字母被排除正是
     * 为了防混淆，出现它们基本可以断定是 OCR 把 1/0/5/U/2 读错了，
     * 这个提示比笼统的「校验位不匹配」有用得多。
     */
    private static FieldValidation validateCreditCode(String value) {
        String rule = "GB 32100-2015";
        if (CheckDigits.containsExcludedLetters(value)) {
            return FieldValidation.fail(rule,
                    "含 GB 32100 排除的字母 I/O/S/V/Z，疑似把 1/0/5/U/2 读错");
        }
        if (value.length() == 15 && value.chars().allMatch(Character::isDigit)) {
            return FieldValidation.fail(rule,
                    "15 位纯数字，是旧版工商注册号而非统一社会信用代码"
                            + "（上游 BusinessLicenseParser 会把它填进 creditCode 字段）");
        }
        if (value.length() != 18) {
            return FieldValidation.fail(rule, "长度为 " + value.length() + " 位，应为 18 位");
        }
        if (!CheckDigits.isValidCreditCode(value)) {
            return FieldValidation.fail(rule, "校验位不匹配，疑似 OCR 误识");
        }
        return FieldValidation.pass(rule);
    }

    private static FieldValidation validateBankCard(String value) {
        String rule = "Luhn（ISO/IEC 7812-1）";
        return CheckDigits.isValidLuhn(value)
                ? FieldValidation.pass(rule)
                : FieldValidation.fail(rule, "Luhn 校验不通过，疑似 OCR 误识或卡号不完整");
    }

    /**
     * 发票金额勾稽：大小写金额一致 + 金额与税额之和等于价税合计。
     *
     * <p>这是票据 OCR 少有的自校验机会。大写金额的字形（壹贰叁肆伍陆柒捌玖）彼此差异极大，
     * OCR 几乎不会读错；小写数字的 3↔8、0↔6 却很容易混淆。两者一比就能发现问题。
     */
    private static void validateInvoiceAmounts(Map<String, Object> fields,
                                               Map<String, FieldValidation> out) {
        String upperRule = "大小写金额一致性";
        BigDecimal upper = text(fields, "totalAmountUpper").map(ChineseAmount::parse).orElse(null);
        BigDecimal lower = text(fields, "totalAmountLower").map(ChineseAmount::parseNumeric).orElse(null);

        if (upper != null && lower != null) {
            out.put("totalAmountUpper", ChineseAmount.equalAmount(upper, lower)
                    ? FieldValidation.pass(upperRule)
                    : FieldValidation.fail(upperRule,
                    "大写金额 " + upper.toPlainString() + " 与小写金额 " + lower.toPlainString()
                            + " 不一致，至少有一边被读错"));
        } else if (upper == null && text(fields, "totalAmountUpper").isPresent()) {
            out.put("totalAmountUpper", FieldValidation.fail(upperRule, "大写金额无法解析，无法与小写金额比对"));
        }

        String reconRule = "金额 + 税额 = 价税合计";
        BigDecimal amount = text(fields, "amount").map(ChineseAmount::parseNumeric).orElse(null);
        BigDecimal tax = text(fields, "taxAmount").map(ChineseAmount::parseNumeric).orElse(null);
        if (amount != null && tax != null && lower != null) {
            out.put("totalAmountLower", ChineseAmount.equalAmount(amount.add(tax), lower)
                    ? FieldValidation.pass(reconRule)
                    : FieldValidation.fail(reconRule,
                    "金额 " + amount.toPlainString() + " + 税额 " + tax.toPlainString()
                            + " = " + amount.add(tax).toPlainString()
                            + "，与价税合计 " + lower.toPlainString() + " 不符"));
        }
    }

    /** 取字段的非空文本值。 */
    private static java.util.Optional<String> text(Map<String, Object> fields, String key) {
        Object v = fields.get(key);
        if (v == null) {
            return java.util.Optional.empty();
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(s);
    }
}
