package com.example.ppocr4j.service;

import com.example.ppocr4j.exception.OcrException;
import com.example.ppocr4j.parser.PassportParser;
import com.example.ppocr4j.web.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.bankcard.BankCardParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.business.BusinessLicenseParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredResult;
import net.dreamlu.mica.ai.ppocr.structured.parser.driver.DriverLicenseParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.idcard.IdCardParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.invoice.InvoiceParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.vehicle.VehicleLicenseParser;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 证件/票据字段级结构化提取（基于 mica-ppocr-structured 1.1.x）。
 *
 * <p>识别走本项目自己的流水线（{@link OcrService}：档次选择、并发闸门、像素校验、
 * doc_ori 自动转正），拿到 OCR 原始结果后再喂给对应解析器的
 * {@code parseResults(List)} 纯函数——因此 tier/rotate/autoRotate 参数
 * 与加速引擎对结构化接口同样生效（Starter 解析器 Bean 内绑定的默认引擎不参与识别）。</p>
 */
@Service
public class OcrParseService {

    /** 支持的证件类型（对外 docType 取值） → 解析函数。 */
    private final Map<String, Function<List<PPOcrV6Result>, ? extends BaseStructuredResult>> parsers;
    /** 归一化 key（去掉连字符）→ 规范 docType，兼容 idCard/id_card/idcard 等写法。 */
    private final Map<String, String> normalizedTypes;
    private final OcrService ocrService;
    private final ObjectMapper objectMapper;

    /**
     * 结构化服务构造函数。
     *
     * <p>说明：
     * <ul>
     *   <li>把支持的证件类型与解析器函数装配到固定有序 Map（接口返回顺序可控）</li>
     *   <li>同时生成一份规范化索引（去掉 -/_）用于兼容不同 docType 写法</li>
     * </ul>
     * </p>
     */
    public OcrParseService(OcrService ocrService, ObjectMapper objectMapper,
                           VehicleLicenseParser vehicleLicenseParser,
                           IdCardParser idCardParser,
                           BankCardParser bankCardParser,
                           DriverLicenseParser driverLicenseParser,
                           BusinessLicenseParser businessLicenseParser,
                           InvoiceParser invoiceParser,
                           PassportParser passportParser) {
        this.ocrService = ocrService;
        this.objectMapper = objectMapper;
        // LinkedHashMap 保序：/api/ocr/parse/types 按此顺序返回
        Map<String, Function<List<PPOcrV6Result>, ? extends BaseStructuredResult>> map = new LinkedHashMap<>();
        map.put("vehicle-license", vehicleLicenseParser::parseResults);
        map.put("id-card", idCardParser::parseResults);
        map.put("bank-card", bankCardParser::parseResults);
        map.put("driver-license", driverLicenseParser::parseResults);
        map.put("business-license", businessLicenseParser::parseResults);
        map.put("invoice", invoiceParser::parseResults);
        // 本项目自定义解析器：护照（ICAO 9303 TD3，MRZ 优先 + 可视区兜底）
        map.put("passport", passportParser::parseResults);
        this.parsers = map;
        Map<String, String> normalized = new LinkedHashMap<>();
        map.keySet().forEach(k -> normalized.put(k.replace("-", ""), k));
        this.normalizedTypes = normalized;
    }

    /** 支持的 docType 列表（供 types 接口与错误提示）。 */
    public List<String> supportedTypes() {
        return List.copyOf(parsers.keySet());
    }

    /**
     * 结构化提取：OCR（tier/rotate/autoRotate 语义同 /api/ocr）→ 字段解析。
     *
     * @return outcome：规范 docType、OCR 原始结果、业务字段 Map、字段坐标 Map
     * @throws OcrException docType 未知(1001)及 OCR 流水线的全部错误码
     */
    public Outcome parse(byte[] imageBytes, String docType, String tier, int rotate, boolean autoRotate) {
        String canonical = canonicalType(docType);
        List<PPOcrV6Result> results = ocrService.recognize(imageBytes, tier, rotate, autoRotate);
        BaseStructuredResult parsed = parsers.get(canonical).apply(results);
        return new Outcome(canonical, results, extractFields(parsed), parsed.getFieldBoxes());
    }

    /**
     * docType 归一化：去除连字符/下划线并转小写，保证前端传入 id_card/id-card/idCard 等兼容。
     *
     * @throws OcrException 当类型为空或不在白名单内
     */
    private String canonicalType(String docType) {
        if (docType == null || docType.isBlank()) {
            throw new OcrException(ErrorCode.INVALID_PARAM, "docType 为空，可选值: " + parsers.keySet());
        }
        String canonical = normalizedTypes.get(docType.trim().toLowerCase().replace("-", "").replace("_", ""));
        if (canonical == null) {
            throw new OcrException(ErrorCode.INVALID_PARAM, "未知证件类型: " + docType + "，可选值: " + parsers.keySet());
        }
        return canonical;
    }

    /**
     * 把解析结果对象转成「纯业务字段」Map：剔除基类的 rawResults / fieldBoxes
     * （分别以 results / fieldBoxes 独立字段返回，避免响应体重复膨胀）。
     */
    private Map<String, Object> extractFields(BaseStructuredResult parsed) {
        Map<String, Object> fields = objectMapper.convertValue(parsed, new TypeReference<>() {});
        fields.remove("rawResults");
        fields.remove("fieldBoxes");
        return fields;
    }

    /**
     * 解析产出。
     *
     * @param docType    规范化后的证件类型
     * @param rawResults OCR 原始结果（含文本框与 rotatedDegrees）
     * @param fields     业务字段（如 plateNo/owner/…，未识别到的字段为 null）
     * @param fieldBoxes 字段名 → 命中文本框坐标列表，供可视化定位
     */
    public record Outcome(String docType, List<PPOcrV6Result> rawResults,
                          Map<String, Object> fields, Map<String, List<int[][]>> fieldBoxes) {}
}
