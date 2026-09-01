package com.example.ppocr4j.parser;

import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredResult;

/**
 * 往来港澳通行证结构化解析结果（卡式电子证，正面资料面）。
 *
 * <p>字段分两组，可靠性不同：
 * <ul>
 *   <li><b>机读码字段</b>（{@link #permitNo} / {@link #birthDate} / {@link #expiryDate}）——
 *       来自卡片正面底部的 30 字符单行机读码，自带四个校验位，
 *       {@link #mrzValid} 为 true 时可直接采信；</li>
 *   <li><b>可视区字段</b>（{@link #nameCn} / {@link #nameEn} / {@link #sex} /
 *       {@link #issuingAuthority} / {@link #placeOfIssue} / {@link #validFrom}）——
 *       机读码里<b>没有</b>这些信息，只能靠标签定位，可靠性低于机读码字段。</li>
 * </ul>
 *
 * <p>本项目无 lombok 依赖，getter/setter 手写（Jackson 序列化依赖 getter，
 * 漏写会静默丢字段，已由 {@code ResultBeanContractTest} 兜住）。
 */
public class HkMacaoPermitResult extends BaseStructuredResult {

    /** 机读码原文（30 字符单行），未识别到时为 null。 */
    private String mrzLine;
    /**
     * 机读码校验位是否全部通过。
     *
     * <p>true = 证件号/有效期/出生日期/综合 四个校验位全部匹配；
     * false = 至少一位不匹配（OCR 误识或图片模糊），字段仍返回但需人工复核；
     * null = 未找到机读码，字段全部来自可视区。
     */
    private Boolean mrzValid;
    /** 机读码证件标识，往来港澳通行证为 {@code CS}。 */
    private String documentType;

    /**
     * 证件号码，9 位。
     *
     * <p>2018-12-03 前为 {@code C}+8 位数字；之后为 {@code C}+字母（不含 I、O）+7 位数字。
     * 号码非终身唯一（换证会变）。
     */
    private String permitNo;
    /** 出生日期，已规范化为 {@code yyyy-MM-dd}。 */
    private String birthDate;
    /** 有效期起始日，{@code yyyy-MM-dd}（仅可视区有，机读码只含截止日）。 */
    private String validFrom;
    /** 有效期截止日，{@code yyyy-MM-dd}。 */
    private String expiryDate;

    /** 中文姓名（可视区）。 */
    private String nameCn;
    /** 拼音姓名（可视区，形如 {@code ZHENGJIAN, YANGBEN}）。 */
    private String nameEn;
    /** 性别，归一化为 {@code M} / {@code F}（可视区）。 */
    private String sex;
    /** 签发机关（可视区，如「中华人民共和国出入境管理局」）。 */
    private String issuingAuthority;
    /** 签发地点（可视区，如「广东」）。 */
    private String placeOfIssue;

    public String getMrzLine() {
        return mrzLine;
    }

    public void setMrzLine(String mrzLine) {
        this.mrzLine = mrzLine;
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

    public String getPermitNo() {
        return permitNo;
    }

    public void setPermitNo(String permitNo) {
        this.permitNo = permitNo;
    }

    public String getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(String birthDate) {
        this.birthDate = birthDate;
    }

    public String getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(String validFrom) {
        this.validFrom = validFrom;
    }

    public String getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(String expiryDate) {
        this.expiryDate = expiryDate;
    }

    public String getNameCn() {
        return nameCn;
    }

    public void setNameCn(String nameCn) {
        this.nameCn = nameCn;
    }

    public String getNameEn() {
        return nameEn;
    }

    public void setNameEn(String nameEn) {
        this.nameEn = nameEn;
    }

    public String getSex() {
        return sex;
    }

    public void setSex(String sex) {
        this.sex = sex;
    }

    public String getIssuingAuthority() {
        return issuingAuthority;
    }

    public void setIssuingAuthority(String issuingAuthority) {
        this.issuingAuthority = issuingAuthority;
    }

    public String getPlaceOfIssue() {
        return placeOfIssue;
    }

    public void setPlaceOfIssue(String placeOfIssue) {
        this.placeOfIssue = placeOfIssue;
    }
}
