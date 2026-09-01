package com.example.ppocr4j;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成回归测试：真实 tiny 引擎 + 仓库自带行驶证测试图。
 * 关键字段断言用于防止 mica-ppocr 升级时识别精度悄悄劣化。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class OcrIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @SuppressWarnings("unchecked")
    private static List<String> texts(Map<String, Object> data) {
        var results = (List<Map<String, Object>>) data.get("results");
        return results.stream().map(r -> (String) r.get("text")).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postImage(String url, MultiValueMap<String, Object> form) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        Map<String, Object> body = rest.postForObject(url, new HttpEntity<>(form, headers), Map.class);
        assertThat(body).isNotNull();
        return body;
    }

    @Test
    @SuppressWarnings("unchecked")
    void recognizesVehicleLicenseKeyFields() {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", new FileSystemResource("test_images/1.png"));
        form.add("tier", "tiny");
        Map<String, Object> body = postImage("/api/ocr", form);

        assertThat(body.get("code")).isEqualTo(0);
        assertThat(body.get("traceId")).isNotNull();
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data.get("tier")).isEqualTo("tiny");
        assertThat((String) data.get("fullText")).isNotBlank();

        // 识别精度回归锚点：升级 mica-ppocr 后这些关键字段必须保持正确
        List<String> texts = texts(data);
        assertThat(texts).contains("中华人民共和国机动车行驶证");
        assertThat(texts).contains("京N99FF7");
        assertThat(texts).anyMatch(t -> t.contains("2017-07-25"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void base64EndpointMatchesMultipart() throws Exception {
        String b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of("test_images/1.png")));
        Map<String, Object> body = rest.postForObject("/api/ocr/base64",
                Map.of("image", b64, "tier", "tiny"), Map.class);
        assertThat(body.get("code")).isEqualTo(0);
        List<String> texts = texts((Map<String, Object>) body.get("data"));
        assertThat(texts).contains("京N99FF7");
    }

    @Test
    void invalidTierReturns1001() {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", new FileSystemResource("test_images/1.png"));
        form.add("tier", "foo");
        Map<String, Object> body = postImage("/api/ocr", form);
        assertThat(body.get("code")).isEqualTo(1001);
    }

    @Test
    void invalidRotateReturns1001() {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", new FileSystemResource("test_images/1.png"));
        form.add("rotate", "45");
        Map<String, Object> body = postImage("/api/ocr", form);
        assertThat(body.get("code")).isEqualTo(1001);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonImagePayloadReturns1002() {
        Map<String, Object> body = rest.postForObject("/api/ocr/base64",
                Map.of("image", Base64.getEncoder().encodeToString("not an image".getBytes())), Map.class);
        assertThat(body.get("code")).isEqualTo(1002);
    }

    @Test
    @SuppressWarnings("unchecked")
    void asyncTaskLifecycle() throws Exception {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", new FileSystemResource("test_images/1.png"));
        form.add("tier", "tiny");
        Map<String, Object> submit = postImage("/api/ocr/tasks", form);
        assertThat(submit.get("code")).isEqualTo(0);
        String taskId = (String) ((Map<String, Object>) submit.get("data")).get("taskId");

        Map<String, Object> data = null;
        for (int i = 0; i < 40; i++) {
            Map<String, Object> body = rest.getForObject("/api/ocr/tasks/" + taskId, Map.class);
            data = (Map<String, Object>) body.get("data");
            if ("DONE".equals(data.get("status")) || "FAILED".equals(data.get("status"))) {
                break;
            }
            Thread.sleep(250);
        }
        assertThat(data.get("status")).isEqualTo("DONE");
        Map<String, Object> result = (Map<String, Object>) data.get("result");
        assertThat((Integer) result.get("count")).isGreaterThan(10);
    }

    @Test
    @SuppressWarnings("unchecked")
    void parsesVehicleLicenseFields() {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", new FileSystemResource("test_images/1.png"));
        form.add("tier", "tiny");
        Map<String, Object> body = postImage("/api/ocr/parse/vehicle-license", form);

        assertThat(body.get("code")).isEqualTo(0);
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data.get("docType")).isEqualTo("vehicle-license");
        // 字段级断言：结构化解析器必须抽准车牌号
        Map<String, Object> fields = (Map<String, Object>) data.get("fields");
        assertThat(fields.get("plateNo")).isEqualTo("京N99FF7");
        // fields 中不应混入基类的 rawResults / fieldBoxes（它们以独立字段返回）
        assertThat(fields).doesNotContainKeys("rawResults", "fieldBoxes");
        assertThat((Map<String, Object>) data.get("fieldBoxes")).containsKey("plateNo");
        assertThat((List<?>) data.get("results")).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void parseTypesAndUnknownType() {
        Map<String, Object> types = rest.getForObject("/api/ocr/parse/types", Map.class);
        assertThat(types.get("code")).isEqualTo(0);
        List<String> list = (List<String>) ((Map<String, Object>) types.get("data")).get("types");
        assertThat(list).contains("vehicle-license", "id-card", "bank-card",
                "driver-license", "business-license", "invoice", "passport");

        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", new FileSystemResource("test_images/1.png"));
        Map<String, Object> body = postImage("/api/ocr/parse/not-a-doc-type", form);
        assertThat(body.get("code")).isEqualTo(1001);
    }

    /**
     * 护照结构化回归：样图为荷兰官方 SPECIMEN（CC0，虚构人物），
     * MRZ 两行完整且 5 个校验位自洽，可作为 MRZ 解析链路的强断言锚点。
     */
    @Test
    @SuppressWarnings("unchecked")
    void parsesPassportMrzFields() {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", new FileSystemResource("test_images/passport-specimen.jpg"));
        form.add("tier", "tiny");
        Map<String, Object> body = postImage("/api/ocr/parse/passport", form);

        assertThat(body.get("code")).isEqualTo(0);
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data.get("docType")).isEqualTo("passport");
        Map<String, Object> fields = (Map<String, Object>) data.get("fields");

        // MRZ 校验位全部自洽——这条挂了说明 OCR 把机读区读错了字符
        assertThat(fields.get("mrzValid")).isEqualTo(true);
        assertThat(fields.get("passportNo")).isEqualTo("SPECI2014");
        assertThat(fields.get("issuingCountry")).isEqualTo("NLD");
        assertThat(fields.get("nationality")).isEqualTo("NLD");
        assertThat(fields.get("documentType")).isEqualTo("P");
        assertThat(fields.get("sex")).isEqualTo("F");
        // MRZ 日期与可视区印刷值互相印证：10 MAA/MAR 1965、09 MAA/MAR 2024
        assertThat(fields.get("birthDate")).isEqualTo("1965-03-10");
        assertThat(fields.get("expiryDate")).isEqualTo("2024-03-09");
        assertThat((String) fields.get("mrzLine2")).hasSize(44);

        // 姓名区的 << 分隔符在 tiny 档会被少读一个，此时不猜姓/名，只保证 nameEn 可用
        assertThat((String) fields.get("nameEn")).contains("BRUIJN", "WILLEKE");

        assertThat(fields).doesNotContainKeys("rawResults", "fieldBoxes");
        assertThat((Map<String, Object>) data.get("fieldBoxes")).containsKeys("mrzLine2", "passportNo");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tiersAndInfoEndpoints() {
        Map<String, Object> tiers = rest.getForObject("/api/ocr/tiers", Map.class);
        assertThat(tiers.get("code")).isEqualTo(0);

        Map<String, Object> info = rest.getForObject("/api/ocr/info", Map.class);
        assertThat(info.get("code")).isEqualTo(0);
        Map<String, Object> data = (Map<String, Object>) info.get("data");
        assertThat(data.get("defaultTier")).isEqualTo("tiny");
    }

    @Test
    @SuppressWarnings("unchecked")
    void readinessProbeUp() {
        Map<String, Object> health = rest.getForObject("/actuator/health/readiness", Map.class);
        assertThat(health.get("status")).isEqualTo("UP");
    }
}
