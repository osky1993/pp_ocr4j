package com.example.ppocr4j.parser.validate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验位算法测试。
 *
 * <p>用例中的号码都是<b>按算法构造</b>的合法号码，不是真实证件号——
 * 校验位算法是确定性的，构造出的号码同样能完整覆盖算法，且没有任何隐私问题。
 */
class CheckDigitsTest {

    // ==================================================================
    // 居民身份证号（ISO 7064 MOD 11-2）
    // ==================================================================

    /** 末位为 X 的合法号码——上游驾驶证解析器的 \\d{15,18} 正好处理不了这种。 */
    private static final String ID_X_TAIL = "11010519491231002X";
    /** 末位为数字的合法号码。 */
    private static final String ID_DIGIT_TAIL = "110101199003077897";

    @Test
    void acceptsValidIdNumbers() {
        // 这两个号码的校验位是按 ISO 7064 MOD 11-2 算出来的，不是编的——
        // 用错样本会让后续所有断言失去意义
        assertThat(CheckDigits.isValidIdNumber(ID_X_TAIL)).isTrue();
        assertThat(CheckDigits.isValidIdNumber(ID_DIGIT_TAIL)).isTrue();
    }

    @Test
    void rejectsIdNumberWithWrongCheckDigit() {
        // 把末位校验码改掉
        assertThat(CheckDigits.isValidIdNumber("110105194912310020")).isFalse();
        assertThat(CheckDigits.isValidIdNumber("110101199003077891")).isFalse();
    }

    @Test
    void rejectsIdNumberWithSingleDigitTypo() {
        // 校验位的价值就在这里：任意一位读错都应被发现（0→8 是 OCR 最常见的错误之一）
        assertThat(CheckDigits.isValidIdNumber(ID_DIGIT_TAIL)).isTrue();
        assertThat(CheckDigits.isValidIdNumber("118101199003077897")).isFalse();
        assertThat(CheckDigits.isValidIdNumber("110101199003877897")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1101051949123100",      // 16 位
            "1101051949123100211",   // 19 位
            "11010519491231002A",    // 末位非法字母
            "1101X519491231002X",    // X 出现在中间——上游正则 [0-9X]{18} 会放行
    })
    void rejectsMalformedIdNumbers(String bad) {
        assertThat(CheckDigits.isValidIdNumber(bad)).isFalse();
    }

    @Test
    void rejectsNullAndEmptyIdNumber() {
        assertThat(CheckDigits.isValidIdNumber(null)).isFalse();
        assertThat(CheckDigits.isValidIdNumber("")).isFalse();
    }

    @Test
    void acceptsLowercaseXTail() {
        assertThat(CheckDigits.isValidIdNumber("11010519491231002x")).isTrue();
    }

    @Test
    void validatesBirthSegment() {
        assertThat(CheckDigits.hasValidBirthSegment(ID_X_TAIL)).isTrue();
        // 13 月
        assertThat(CheckDigits.hasValidBirthSegment("110105194913310025")).isFalse();
        // 未来日期
        assertThat(CheckDigits.hasValidBirthSegment("110105209912310025")).isFalse();
    }

    @Test
    void derivesGenderFromSeventeenthDigit() {
        // 第 17 位奇数为男、偶数为女
        assertThat(CheckDigits.genderFromIdNumber(ID_X_TAIL)).isEqualTo("F");
        assertThat(CheckDigits.genderFromIdNumber(ID_DIGIT_TAIL)).isEqualTo("M");
        assertThat(CheckDigits.genderFromIdNumber("bad")).isNull();
    }

    // ==================================================================
    // 统一社会信用代码（GB 32100-2015）
    // ==================================================================

    @Test
    void acceptsValidCreditCode() {
        // 构造：前 17 位任取合法字符，第 18 位按 GB 32100 算出
        String code = buildValidCreditCode("91350100M000100Y4");
        assertThat(CheckDigits.isValidCreditCode(code)).isTrue();
    }

    @Test
    void rejectsCreditCodeWithWrongCheckDigit() {
        String code = buildValidCreditCode("91350100M000100Y4");
        char wrong = code.charAt(17) == '0' ? '1' : '0';
        assertThat(CheckDigits.isValidCreditCode(code.substring(0, 17) + wrong)).isFalse();
    }

    @Test
    void rejectsCreditCodeContainingExcludedLetters() {
        // I/O/S/V/Z 不在 GB 32100 的 31 字符集里，出现即非法
        assertThat(CheckDigits.isValidCreditCode("91350I00M000100Y40")).isFalse();
        assertThat(CheckDigits.isValidCreditCode("91350O00M000100Y40")).isFalse();
    }

    @Test
    void detectsExcludedLetters() {
        // 这五个字母被排除正是为了防混淆，出现它们基本可断定 OCR 把 1/0/5/U/2 读错了
        assertThat(CheckDigits.containsExcludedLetters("91350I00M000100Y40")).isTrue();
        assertThat(CheckDigits.containsExcludedLetters("91350S00M000100Y40")).isTrue();
        assertThat(CheckDigits.containsExcludedLetters("91350V00M000100Y40")).isTrue();
        assertThat(CheckDigits.containsExcludedLetters("91350Z00M000100Y40")).isTrue();
        assertThat(CheckDigits.containsExcludedLetters("91350100M000100Y40")).isFalse();
        assertThat(CheckDigits.containsExcludedLetters(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "91350100M000100Y4", "91350100M000100Y400"})
    void rejectsCreditCodeOfWrongLength(String bad) {
        assertThat(CheckDigits.isValidCreditCode(bad)).isFalse();
    }

    /** 按 GB 32100 给前 17 位算出校验码，拼成合法的 18 位代码。 */
    private static String buildValidCreditCode(String first17) {
        String charset = "0123456789ABCDEFGHJKLMNPQRTUWXY";
        int[] weights = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += charset.indexOf(first17.charAt(i)) * weights[i];
        }
        int c = 31 - (sum % 31);
        return first17 + charset.charAt(c == 31 ? 0 : c);
    }

    // ==================================================================
    // 银行卡号（Luhn）
    // ==================================================================

    @Test
    void acceptsValidLuhnNumbers() {
        // 公开的测试卡号（各支付网络文档里用于联调，非真实账户）
        assertThat(CheckDigits.isValidLuhn("4111111111111111")).isTrue();
        assertThat(CheckDigits.isValidLuhn("5500005555555559")).isTrue();
        assertThat(CheckDigits.isValidLuhn("6011000990139424")).isTrue();
    }

    @Test
    void toleratesSpacesAndDashes() {
        assertThat(CheckDigits.isValidLuhn("4111 1111 1111 1111")).isTrue();
        assertThat(CheckDigits.isValidLuhn("4111-1111-1111-1111")).isTrue();
    }

    @Test
    void rejectsLuhnWithTransposedOrWrongDigits() {
        assertThat(CheckDigits.isValidLuhn("4111111111111112")).isFalse();
        assertThat(CheckDigits.isValidLuhn("4111111111111121")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"41111111111", "41111111111111111111", "4111a11111111111"})
    void rejectsMalformedCardNumbers(String bad) {
        assertThat(CheckDigits.isValidLuhn(bad)).isFalse();
    }

    @Test
    void rejectsNullCardNumber() {
        assertThat(CheckDigits.isValidLuhn(null)).isFalse();
    }
}
