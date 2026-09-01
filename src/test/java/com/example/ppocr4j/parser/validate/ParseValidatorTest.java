package com.example.ppocr4j.parser.validate;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字段校验分派测试。
 *
 * <p>本类的用例大多直接对应 mica-ppocr-structured 内置解析器<b>已核实的缺陷</b>——
 * 这些缺陷改不了（在 jar 里），后处理的职责就是把它们暴露出来，
 * 让调用方知道哪些字段不能直接采信。
 */
class ParseValidatorTest {

    private final ParseValidator validator = new ParseValidator();

    /** 末位为 X 的合法身份证号，校验位按 ISO 7064 MOD 11-2 算出。 */
    private static final String ID_X_TAIL = "11010519491231002X";
    private static final String ID_VALID = "110101199003077897";

    private static Map<String, Object> fields(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ==================================================================
    // 身份证 / 驾驶证
    // ==================================================================

    @Test
    void passesValidIdNumber() {
        var out = validator.validate("id-card", fields("idNumber", ID_VALID));

        assertThat(out).containsKey("idNumber");
        assertThat(out.get("idNumber").valid()).isTrue();
        assertThat(out.get("idNumber").rule()).contains("7064");
    }

    @Test
    void failsIdNumberWithBadCheckDigit() {
        var out = validator.validate("id-card", fields("idNumber", "110101199003077891"));

        assertThat(out.get("idNumber").valid()).isFalse();
        assertThat(out.get("idNumber").note()).contains("校验位不匹配");
    }

    /**
     * 上游 {@code DriverLicenseParser} 的正则是 {@code \\d{15,18}}，不接受末位 X。
     * 末位为 X 的证号（约 1/11 概率）会被兜底逻辑截断成 17 位纯数字返回。
     *
     * <p>后处理拿不回那个 X，但能认出这个特征并给出可操作的提示——
     * 这就是「包装而非重写」方案的能力边界，必须让调用方看见。
     */
    @Test
    void recognizesTruncatedXTailFromUpstreamBug() {
        String truncated = ID_X_TAIL.substring(0, 17);   // 上游会返回的错值
        var out = validator.validate("driver-license", fields("licenseNumber", truncated));

        assertThat(out.get("licenseNumber").valid()).isFalse();
        assertThat(out.get("licenseNumber").note())
                .contains("17")
                .contains("X")
                .contains("DriverLicenseParser");
    }

    @Test
    void flagsFifteenDigitLegacyIdNumber() {
        var out = validator.validate("id-card", fields("idNumber", "110105491231002"));

        assertThat(out.get("idNumber").valid()).isFalse();
        assertThat(out.get("idNumber").note()).contains("15 位");
    }

    @Test
    void validatesChinesePassportPersonalNumberAsIdNumber() {
        // 中国护照的 MRZ 可选数据区放的是公民身份号码
        var out = validator.validate("passport",
                fields("issuingCountry", "CHN", "personalNumber", ID_VALID));

        assertThat(out.get("personalNumber").valid()).isTrue();
    }

    /**
     * 非中国护照的 personalNumber 含义完全不同（荷兰护照放的是本国个人号），
     * 按身份证规则校验只会产生误报。
     *
     * <p>这个误报是端到端测试发现的：荷兰 SPECIMEN 样本的 personalNumber 是
     * {@code 999999990}，一度被报成「长度为 9 位，应为 18 位」。误报会让调用方
     * 逐渐忽略整个 validations，比不校验更有害。
     */
    @Test
    void skipsPersonalNumberForNonChinesePassport() {
        var out = validator.validate("passport",
                fields("issuingCountry", "NLD", "personalNumber", "999999990"));

        assertThat(out).doesNotContainKey("personalNumber");
    }

    @Test
    void skipsPersonalNumberWhenIssuingCountryUnknown() {
        // 签发国没读出来时不做假设
        var out = validator.validate("passport", fields("personalNumber", "999999990"));

        assertThat(out).doesNotContainKey("personalNumber");
    }

    // ==================================================================
    // 统一社会信用代码
    // ==================================================================

    @Test
    void passesValidCreditCode() {
        var out = validator.validate("business-license", fields("creditCode", validCreditCode()));

        assertThat(out.get("creditCode").valid()).isTrue();
        assertThat(out.get("creditCode").rule()).contains("32100");
    }

    /**
     * 上游正则用的是 {@code [0-9A-Z]} 全字母表，而 GB 32100 明确排除 I/O/S/V/Z——
     * 排除它们正是为了防止与 1/0/5/U/2 混淆，恰恰是 OCR 最容易犯的错。
     */
    @Test
    void flagsExcludedLettersWithActionableHint() {
        var out = validator.validate("business-license", fields("creditCode", "91350I00M000100Y40"));

        assertThat(out.get("creditCode").valid()).isFalse();
        assertThat(out.get("creditCode").note())
                .contains("I/O/S/V/Z")
                .contains("1/0/5/U/2");
    }

    /**
     * 上游遇到旧版 15 位工商注册号时，会直接把它塞进 creditCode 字段，
     * 调用方无从区分拿到的是信用代码还是注册号。
     */
    @Test
    void flagsLegacyRegistrationNumberInCreditCodeField() {
        var out = validator.validate("business-license", fields("creditCode", "350100000012345"));

        assertThat(out.get("creditCode").valid()).isFalse();
        assertThat(out.get("creditCode").note()).contains("工商注册号");
    }

    @Test
    void validatesInvoiceTaxNumbers() {
        var out = validator.validate("invoice",
                fields("buyerTaxNo", validCreditCode(), "sellerTaxNo", "91350I00M000100Y40"));

        assertThat(out.get("buyerTaxNo").valid()).isTrue();
        assertThat(out.get("sellerTaxNo").valid()).isFalse();
    }

    // ==================================================================
    // 银行卡
    // ==================================================================

    @Test
    void validatesBankCardWithLuhn() {
        assertThat(validator.validate("bank-card", fields("cardNumber", "4111111111111111"))
                .get("cardNumber").valid()).isTrue();
        assertThat(validator.validate("bank-card", fields("cardNumber", "4111111111111112"))
                .get("cardNumber").valid()).isFalse();
    }

    // ==================================================================
    // 发票金额勾稽
    // ==================================================================

    @Test
    void passesConsistentInvoiceAmounts() {
        var out = validator.validate("invoice", fields(
                "totalAmountUpper", "壹仟壹佰叁拾元整",
                "totalAmountLower", "1130.00",
                "amount", "1000.00",
                "taxAmount", "130.00"));

        assertThat(out.get("totalAmountUpper").valid()).isTrue();
        assertThat(out.get("totalAmountLower").valid()).isTrue();
    }

    @Test
    void detectsUpperLowerAmountMismatch() {
        // 小写金额的 3 被 OCR 读成 8；大写「叁」字形独特不会读错
        var out = validator.validate("invoice", fields(
                "totalAmountUpper", "叁佰元整",
                "totalAmountLower", "800.00"));

        assertThat(out.get("totalAmountUpper").valid()).isFalse();
        assertThat(out.get("totalAmountUpper").note()).contains("300").contains("800");
    }

    @Test
    void detectsBrokenReconciliation() {
        var out = validator.validate("invoice", fields(
                "totalAmountUpper", "壹仟壹佰叁拾元整",
                "totalAmountLower", "1130.00",
                "amount", "1000.00",
                "taxAmount", "150.00"));

        assertThat(out.get("totalAmountLower").valid()).isFalse();
        assertThat(out.get("totalAmountLower").note()).contains("1150").contains("1130");
    }

    @Test
    void flagsUnparseableUpperAmount() {
        var out = validator.validate("invoice", fields(
                "totalAmountUpper", "无法识别的金额", "totalAmountLower", "100.00"));

        assertThat(out.get("totalAmountUpper").valid()).isFalse();
        assertThat(out.get("totalAmountUpper").note()).contains("无法解析");
    }

    // ==================================================================
    // 边界
    // ==================================================================

    @Test
    void skipsNullAndBlankFields() {
        Map<String, Object> withNulls = new HashMap<>();
        withNulls.put("idNumber", null);
        assertThat(validator.validate("id-card", withNulls)).isEmpty();
        assertThat(validator.validate("id-card", fields("idNumber", "   "))).isEmpty();
    }

    @Test
    void returnsEmptyForDocTypeWithoutRules() {
        // 行驶证没有带校验位的字段，不该凭空造出校验结论
        assertThat(validator.validate("vehicle-license", fields("plateNo", "京N99FF7"))).isEmpty();
    }

    @Test
    void returnsEmptyForNullOrEmptyFields() {
        assertThat(validator.validate("id-card", null)).isEmpty();
        assertThat(validator.validate("id-card", Map.of())).isEmpty();
    }

    @Test
    void doesNotModifyFieldValues() {
        // 校验只给判断，绝不改值
        Map<String, Object> input = fields("idNumber", "110101199003077891");
        validator.validate("id-card", input);
        assertThat(input.get("idNumber")).isEqualTo("110101199003077891");
    }

    /** 按 GB 32100 构造一个合法的 18 位统一社会信用代码。 */
    private static String validCreditCode() {
        String charset = "0123456789ABCDEFGHJKLMNPQRTUWXY";
        int[] weights = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};
        String first17 = "91350100M000100Y4";
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += charset.indexOf(first17.charAt(i)) * weights[i];
        }
        int c = 31 - (sum % 31);
        return first17 + charset.charAt(c == 31 ? 0 : c);
    }
}
