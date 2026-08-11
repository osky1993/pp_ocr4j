# pp-ocr4j

Spring Boot 3.x 集成 [mica-ppocr](https://github.com/lets-mica/mica-ppocr) 的示例项目，
参考文章：《Java 首款 PP-OCR 原生库：零 Python 依赖，Maven 引入即用》（微信公众号）。

mica-ppocr 是 PP-OCRv6 文字检测 + 识别的 Java 17 原生实现（如梦技术出品，Apache 2.0）：

- 纯 ONNX Runtime 推理，零 Python / PaddlePaddle 依赖，OpenCV 与 ONNX Runtime 原生库由 Maven 自动拉取；
- bit-exact 移植自 Python 参考实现，默认 CPU 单线程，结果与 Python 版逐位一致；
- 三档模型（tiny / small / medium），Spring Boot Starter 配置即用。

> 注意：文章中依赖坐标写的是 `net.dreamlu.mica.ai`，Maven Central 上实际发布的
> groupId 是 **`net.dreamlu`**（本项目已按实际坐标引入，版本 `1.0.1`）；
> `PPOcrV6Result` 实际位于 `net.dreamlu.mica.ai.ppocr.engine` 包（文章写的 `config` 包有误）。

## 环境要求

- JDK 17+（本机验证使用 Temurin 21）
- Maven 3.6+
- Windows / Linux / macOS 均可（原生库自动适配）

## 项目结构

```
pp_ocr4j/
├── pom.xml                                   # Spring Boot 3.5.x + mica-ppocr-spring-boot-starter:1.0.1
├── models/ppocr-v6/tiny/                     # tiny 档模型（det 1.7MB + rec 4.3MB + 字符表）
│   ├── det.onnx
│   ├── rec.onnx
│   └── dict.txt
├── test_images/1.png                         # 行驶证测试图（来自 mica-ppocr 仓库）
└── src/main/
    ├── resources/application.yml             # mica.ai.ppocr.* 模型路径配置
    └── java/com/example/ppocr4j/
        ├── PpOcr4jApplication.java
        ├── config/OcrTierCustomizer.java     # PPOCRPropertiesCustomizer SPI：按环境变量切换模型档次
        ├── service/OcrService.java           # 注入 PPOcrV6Engine
        └── web/
            ├── OcrController.java            # REST 接口
            └── OcrResponse.java              # 返回 DTO
```

## 运行

```bash
mvn spring-boot:run
```

或打包后运行（注意工作目录需在项目根目录，模型路径为相对路径）：

```bash
mvn -DskipTests package && java -jar target/pp-ocr4j-0.0.1-SNAPSHOT.jar
```

## 内嵌前端页面

启动后访问 <http://localhost:8080/>（`src/main/resources/static/index.html`，单文件、零外部依赖）：

- 拖拽 / 点击选择 / 直接粘贴截图上传图片；
- **旋转转正**：左转/右转按钮以 90° 为步进旋转图片，识别时上传旋转后的图——
  mica-ppocr 的流水线没有方向分类器（cls 模型），横拍/倒置的照片必须先转正，否则识别质量崩溃；
- **模型档次切换**：工具栏可选 tiny / small / medium（从 `GET /api/ocr/tiers` 加载，
  模型文件缺失的档次自动禁用；某档首次识别时后端懒加载引擎，稍慢，之后走缓存）；
- 调用 `POST /api/ocr` 识别，画布上叠加文字框（绿色 = 置信度 ≥ 0.9，橙色 = 较低）；
- 右侧结果列表与画布联动：悬停列表项高亮对应文字框，点击画布文字框定位列表项；
- 一键复制全部识别文本。

## 接口

### 1. 演示接口（识别自带行驶证测试图）

```bash
curl http://localhost:8080/api/ocr/demo
```

### 2. 上传图片识别

```bash
curl -F "file=@test_images/1.png" http://localhost:8080/api/ocr
```

可选参数：`tier`（模型档次，缺省用默认档）、`rotate`（识别前顺时针旋转 0/90/180/270，
横拍图转正用）、`autoRotate`（true 时四方向自动试探选优，约 4 倍 tiny 耗时，忽略 rotate）：

```bash
curl -F "file=@test_images/1.png" -F "tier=medium" -F "rotate=90" http://localhost:8080/api/ocr
```

### 2b. base64 识别（JSON 输入）

适合从 MQ / 内部 RPC 携带图片字节的调用方，参数与 multipart 接口一致，
`image` 支持裸 base64 或 data URL：

```bash
curl -X POST -H "Content-Type: application/json" -d '{"image":"<base64>","tier":"small","rotate":0,"autoRotate":false}' http://localhost:8080/api/ocr/base64
```

### 3. 模型档次列表

```bash
curl http://localhost:8080/api/ocr/tiers
```

返回各档 `available`（模型文件是否就位）与 `loaded`（引擎是否已加载）状态。

返回示例（tiny 模型，Apple Silicon 约 1.1s）：

```json
{
  "source": "1.png",
  "count": 36,
  "costMs": 1081,
  "results": [
    { "text": "中华人民共和国机动车行驶证", "score": 0.9997, "box": [[696,330],[2223,320],[2224,442],[697,452]] },
    { "text": "京N99FF7", "score": 0.991, "box": [...] }
  ]
}
```

## 切换模型档次

三档模型按需选择（本项目已全部下载就位；medium 在仓库中为 zip 包，需解压）：

| 档次 | det | rec | 字符表 | 定位 |
|------|-----|-----|--------|------|
| tiny | 1.7 MB | 4.3 MB | ~2855 字符 | 轻量优先，速度快 |
| small | 9.4 MB | 20.2 MB | ~2855 字符 | 均衡，推荐默认 |
| medium | 59.2 MB | 73.0 MB | ~7180 字符 | 精度优先，覆盖生僻字 |

下载其他档次模型（来自 mica-ppocr 仓库 `models/` 目录）：

```bash
mkdir -p models/ppocr-v6/small && for f in det.onnx rec.onnx dict.txt; do curl -L "https://raw.githubusercontent.com/lets-mica/mica-ppocr/master/models/ppocr-v6/small/$f" -o "models/ppocr-v6/small/$f"; done
```

切换方式三选一：

- **前端页面 / 接口 `tier` 参数**（推荐）：运行时动态切换，`OcrEngineManager` 按档懒加载并缓存引擎，
  调参项（阈值、批大小、线程数等）继承 `application.yml` 的基底配置，三档行为一致；
- 改 `application.yml` 中 `mica.ai.ppocr.*-path` 三项，调整**默认档**；
- 或利用本项目内置的 `OcrTierCustomizer`（PPOCRPropertiesCustomizer SPI），启动时用环境变量改默认档：

```bash
PPOCR_TIER=small mvn spring-boot:run
```

## 使用建议（参数调优）

详见 [USAGE.md](USAGE.md)，内容索引：

| 章节 | 内容 |
|------|------|
| [一、流水线与参数全景](USAGE.md#一流水线与参数全景) | 全部参数的默认值、所属阶段与语义（含不可配置的硬编码值） |
| [二、实测基准](USAGE.md#二实测基准先建立直觉) | 默认 / `max/960` / 多线程三种配置的耗时与质量对比 |
| [三、分场景推荐](USAGE.md#三分场景推荐) | 通用文档、拍照大图、密集小字、证照票据、模糊图、截图、高并发、Python 对拍等 8 类场景的 yml 配置 |
| [四、GPU / 加速器](USAGE.md#四gpu--加速器的真实情况) | `prefer-accelerator` 的真实行为与 CUDA 依赖替换 |
| [五、容易踩的坑](USAGE.md#五容易踩的坑) | `rec-image-shape` 陷阱、超长文本行、内存保护、调参先后顺序 |

## 相关链接

- GitHub: https://github.com/lets-mica/mica-ppocr
- Gitee: https://gitee.com/dreamlu/mica-ppocr
- 许可证: Apache License 2.0
