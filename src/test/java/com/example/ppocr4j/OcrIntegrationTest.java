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
                "driver-license", "business-license", "invoice");

        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", new FileSystemResource("test_images/1.png"));
        Map<String, Object> body = postImage("/api/ocr/parse/passport", form);
        assertThat(body.get("code")).isEqualTo(1001);
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
