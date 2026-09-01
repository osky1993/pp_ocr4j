package com.example.ppocr4j.contract;

import com.example.ppocr4j.service.OcrParseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 门禁 G7 / G5：文档与固定测试集必须跟上代码。
 *
 * <p>每新增一种 docType，README 的类型表、前端下拉框、固定测试集都要同步更新。
 * 靠人记必然会漏——尤其是并行开发多个解析器、最后统一收口注册的时候。
 * 这三条断言把「忘了同步」变成一次测试失败。
 */
@SpringBootTest
@ActiveProfiles("dev")
class DocsSyncTest {

    @Autowired
    private OcrParseService parseService;

    @Test
    void readmeDocumentsEverySupportedDocType() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        List<String> missing = new ArrayList<>();
        for (String docType : parseService.supportedTypes()) {
            // README 类型表里的写法是 `docType`（反引号包裹）
            if (!readme.contains("`" + docType + "`")) {
                missing.add(docType);
            }
        }
        assertThat(missing)
                .as("这些 docType 已注册但 README 的类型表没写，调用方无从知道它们存在")
                .isEmpty();
    }

    @Test
    void frontendSelectorOffersEverySupportedDocType() throws IOException {
        String html = Files.readString(Path.of("src/main/resources/static/index.html"));
        List<String> missing = new ArrayList<>();
        for (String docType : parseService.supportedTypes()) {
            if (!html.contains("value=\"" + docType + "\"")) {
                missing.add(docType);
            }
        }
        assertThat(missing)
                .as("这些 docType 已注册但演示页下拉框里没有")
                .isEmpty();
    }

    /**
     * 已知没有固定测试集的 docType，以及原因。
     *
     * <p>这些都是 mica-ppocr-structured 内置的解析器：能力由上游提供，但本项目
     * <b>没有对应的合规样图</b>（真实证件图禁止入库，见 {@code test_images/README.md}），
     * 因此它们的字段准确率目前无从统计——这是一个<b>真实存在的质量缺口</b>，
     * 在这里显式登记而不是让它隐形。
     *
     * <p>取得合规样图后，应当补 fixture 并从本清单移除。<b>新增自建解析器一律不得加进这里</b>：
     * 自建解析器可以用构造的 OCR 文本框做单元测试，没有理由缺少准确率覆盖。
     */
    private static final java.util.Set<String> KNOWN_UNCOVERED = java.util.Set.of(
            "id-card",           // 无合规样图：真实身份证不能入库
            "bank-card",         // 无合规样图
            "driver-license",    // 无合规样图
            "business-license",  // 无合规样图
            "invoice");          // 无合规样图（含真实税号与金额）

    /**
     * 门禁 G3 的前提：每个 docType 要么有固定测试集，要么在 {@link #KNOWN_UNCOVERED} 里
     * 带原因显式豁免。
     *
     * <p>没有 fixture 又没登记的 docType 等于「注册了但从未验证过准确率」，
     * 这正是 quality-gate.sh 要拦住的情况。豁免必须是显式动作——它会出现在 diff 里被看见，
     * 而不是测试悄悄放过。
     */
    @Test
    void everyDocTypeHasQualityFixture() throws IOException {
        Path dir = Path.of("test_images/fixtures");
        assertThat(dir).as("固定测试集目录不存在").exists();

        List<String> covered;
        try (var paths = Files.list(dir)) {
            covered = paths.filter(p -> p.toString().endsWith(".json"))
                    .map(p -> {
                        try {
                            return Files.readString(p);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
        }

        List<String> missing = new ArrayList<>();
        for (String docType : parseService.supportedTypes()) {
            if (KNOWN_UNCOVERED.contains(docType)) {
                continue;
            }
            boolean hit = covered.stream().anyMatch(c -> c.contains("\"docType\": \"" + docType + "\""));
            if (!hit) {
                missing.add(docType);
            }
        }
        assertThat(missing)
                .as("这些 docType 既没有 fixture，也没在 KNOWN_UNCOVERED 里登记原因")
                .isEmpty();
    }

    /**
     * 豁免清单不许留僵尸条目：某个 docType 一旦补上了 fixture，就必须从
     * {@link #KNOWN_UNCOVERED} 移除，否则它会永久失去准确率门禁保护而无人察觉。
     */
    @Test
    void uncoveredListHasNoStaleEntries() throws IOException {
        List<String> registered = parseService.supportedTypes();
        List<String> stale = KNOWN_UNCOVERED.stream()
                .filter(t -> !registered.contains(t))
                .sorted()
                .toList();
        assertThat(stale)
                .as("这些 docType 已不在注册表里，豁免条目该删了")
                .isEmpty();

        Path dir = Path.of("test_images/fixtures");
        List<String> nowCovered = new ArrayList<>();
        try (var paths = Files.list(dir)) {
            List<String> contents = paths.filter(p -> p.toString().endsWith(".json"))
                    .map(p -> {
                        try {
                            return Files.readString(p);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
            for (String docType : KNOWN_UNCOVERED) {
                if (contents.stream().anyMatch(c -> c.contains("\"docType\": \"" + docType + "\""))) {
                    nowCovered.add(docType);
                }
            }
        }
        assertThat(nowCovered)
                .as("这些 docType 已经有 fixture 了，请从 KNOWN_UNCOVERED 移除以恢复门禁保护")
                .isEmpty();
    }
}
