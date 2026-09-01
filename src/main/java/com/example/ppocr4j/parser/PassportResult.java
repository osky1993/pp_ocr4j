package com.example.ppocr4j.parser;

import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredResult;

/**
 * 护照 OCR 结构化解析结果（ICAO 9303 TD3 版面）。
 *
 * <p>字段分两组，来源不同、可靠性不同：
 * <ul>
 *   <li><b>MRZ 机读区字段</b>（{@link #passportNo} / {@link #birthDate} / {@link #expiryDate} 等）——
 *       来自资料页底部两行 44 字符机读码。格式为国际标准且自带校验位，
 *       {@link #mrzValid} 为 true 时这些字段可信度最高；</li>
 *   <li><b>可视区（VIZ）字段</b>（{@link #nameCn} / {@link #placeOfBirth} / {@link #authority} 等）——
 *       来自版面上的中英文标签定位。MRZ 里没有中文姓名、签发地、签发机关，
 *       这些只能从可视区取。</li>
 * </ul>
 *
 * <p>继承 {@link BaseStructuredResult} 获得 {@code rawResults}（全部 OCR 框）
 * 与 {@code fieldBoxes}（字段名 → box 坐标，供前端高亮）。
 *
 * <p>本项目无 lombok 依赖，因此 getter/setter 手写（Jackson 序列化依赖 getter）。
 */
public class PassportResult extends BaseStructuredResult {

    // ==================================================================
    // MRZ 机读区原文与校验状态
    // ==================================================================

    /** MRZ 第一行原文（清洗后，TD3 标准 44 字符），未识别到时为 null。 */
    private String mrzLine1;
    /** MRZ 第二行原文（清洗后，TD3 标准 44 字符），未识别到时为 null。 */
    private String mrzLine2;
    /**
     * MRZ 校验位是否全部通过。
     *
     * <p>true = 护照号/出生日期/有效期/综合 四个校验位全部匹配，字段基本可以直接采信；
     * false = 至少一位不匹配（OCR 误识或图片模糊），字段仍会返回但需人工复核；
     * null = 没有找到 MRZ，本组字段全部来自可视区或为空。
     */
    private Boolean mrzValid;

    // ==================================================================
    // MRZ 解析出的字段
    // ==================================================================

    /** 证件类型，护照为 "P"（MRZ 第一行首字符）。 */
    private String documentType;
    /** 签发国三字码，如 "CHN" / "NLD"（MRZ 第一行 3~5 位）。 */
    private String issuingCountry;
    /** 护照号码（MRZ 第二行前 9 位，去除填充符）。 */
    private String passportNo;
    /** 持照人国籍三字码，如 "CHN"。 */
    private String nationality;
    /**
     * 拼音全名（MRZ 姓名区去填充符后把 {@code <} 还原成空格）。
     *
     * <p>始终尽力填充：即使 OCR 把姓名分隔符 {@code <<} 少读成一个 {@code <}
     * 导致姓/名无法可靠切分，本字段仍可用。
     */
    private String nameEn;
    /**
     * 姓（拼音大写，MRZ 姓名区 {@code <<} 之前部分）。
     *
     * <p><b>仅在 MRZ 姓名区确实出现 {@code <<} 分隔符时才填充</b>；OCR 漏读分隔符时
     * 置 null 而不是瞎猜——猜错会把复姓（如 "DE BRUIJN"）切成错误的姓名组合。
     * 此时请用 {@link #nameEn} 或可视区字段。
     */
    private String surname;
    /** 名（拼音大写，MRZ 姓名区 {@code <<} 之后部分，多个名以空格分隔）。填充条件同 {@link #surname}。 */
    private String givenNames;
    /** 性别，"M" / "F" / "X"（MRZ 未指定时为 X）。 */
    private String sex;
    /** 出生日期，已规范化为 {@code yyyy-MM-dd}（MRZ 原文为 YYMMDD）。 */
    private String birthDate;
    /** 有效期至，已规范化为 {@code yyyy-MM-dd}（MRZ 原文为 YYMMDD）。 */
    private String expiryDate;
    /**
     * 个人号码（MRZ 可选数据区）。
     *
     * <p>中国电子普通护照在此处放<b>公民身份号码</b>；其他国家含义各异，也可能为空。
     */
    private String personalNumber;

    // ==================================================================
    // 可视区（VIZ）字段：MRZ 里没有，只能从版面标签取
    // ==================================================================

    /** 中文姓名（可视区「姓名/Name」，仅中国护照有）。 */
    private String nameCn;
    /** 出生地点（可视区「出生地点/Place of birth」）。 */
    private String placeOfBirth;
    /** 签发地点（可视区「签发地点/Place of issue」）。 */
    private String placeOfIssue;
    /** 签发日期，已规范化为 {@code yyyy-MM-dd}（可视区「签发日期/Date of issue」）。 */
    private String issueDate;
    /** 签发机关（可视区「签发机关/Authority」，如「公安部出入境管理局」）。 */
    private String authority;

    public String getMrzLine1() {
        return mrzLine1;
    }

    public void setMrzLine1(String mrzLine1) {
        this.mrzLine1 = mrzLine1;
    }

    public String getMrzLine2() {
        return mrzLine2;
    }

    public void setMrzLine2(String mrzLine2) {
        this.mrzLine2 = mrzLine2;
    }

    public Boolean getMrzValid() {
        return mrzValid;
    }

    public void setMrzValid(Boolean mrzValid) {
        this.mrzValid = mrzValid;
    }

    public String getDocumentType() {
        return documentType;
    }

    public void setDocumentType(String documentType) {
        this.documentType = documentType;
    }

    public String getIssuingCountry() {
        return issuingCountry;
    }

    public void setIssuingCountry(String issuingCountry) {
        this.issuingCountry = issuingCountry;
    }

    public String getPassportNo() {
        return passportNo;
    }

    public void setPassportNo(String passportNo) {
        this.passportNo = passportNo;
    }

    public String getNationality() {
        return nationality;
    }

    public void setNationality(String nationality) {
        this.nationality = nationality;
    }

    public String getNameEn() {
        return nameEn;
    }

    public void setNameEn(String nameEn) {
        this.nameEn = nameEn;
    }

    public String getSurname() {
        return surname;
    }

    public void setSurname(String surname) {
        this.surname = surname;
    }

    public String getGivenNames() {
        return givenNames;
    }

    public void setGivenNames(String givenNames) {
        this.givenNames = givenNames;
    }

    public String getSex() {
        return sex;
    }

    public void setSex(String sex) {
        this.sex = sex;
    }

    public String getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(String birthDate) {
        this.birthDate = birthDate;
    }

    public String getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(String expiryDate) {
        this.expiryDate = expiryDate;
    }

    public String getPersonalNumber() {
        return personalNumber;
    }

    public void setPersonalNumber(String personalNumber) {
        this.personalNumber = personalNumber;
    }

    public String getNameCn() {
        return nameCn;
    }

    public void setNameCn(String nameCn) {
        this.nameCn = nameCn;
    }

    public String getPlaceOfBirth() {
        return placeOfBirth;
    }

    public void setPlaceOfBirth(String placeOfBirth) {
        this.placeOfBirth = placeOfBirth;
    }

    public String getPlaceOfIssue() {
        return placeOfIssue;
    }

    public void setPlaceOfIssue(String placeOfIssue) {
        this.placeOfIssue = placeOfIssue;
    }

    public String getIssueDate() {
        return issueDate;
    }

    public void setIssueDate(String issueDate) {
        this.issueDate = issueDate;
    }

    public String getAuthority() {
        return authority;
    }

    public void setAuthority(String authority) {
        this.authority = authority;
    }
}
