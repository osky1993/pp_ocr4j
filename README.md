# pp-ocr4j

独立运行的 OCR 组件：基于 [mica-ppocr](https://github.com/lets-mica/mica-ppocr)
（PP-OCRv6 的 Java 17 原生实现，纯 ONNX Runtime 本地推理，Apache 2.0），
以 Spring Boot 3.x 服务形态对内网系统提供文字识别能力。数据不出内网，零 Python 依赖。

> 起源：参考文章《Java 首款 PP-OCR 原生库：零 Python 依赖，Maven 引入即用》搭建的示例项目，
> 现已按「内部可信接口调用的独立组件」定位补齐工程化能力。
>
> 注意：文章中依赖坐标写的是 `net.dreamlu.mica.ai`，Maven Central 上实际发布的 groupId 是
> **`net.dreamlu`**（本项目版本 `1.0.1`）；`PPOcrV6Result` 实际位于
> `net.dreamlu.mica.ai.ppocr.engine` 包（文章写的 `config` 包有误）。

## 能力一览

- **三档模型**（tiny/small/medium）请求级动态切换，按档懒加载、共享调参基底；
- **同步 / 异步**识别接口，multipart 与 base64 双输入形态；
- **旋转转正**：`rotate` 指定角度或 `autoRotate` 四方向自动试探（流水线无方向分类器，横拍图必须转正）;
- **资源保护**：并发闸门（超限即拒 2001）、识别超时（2002）、像素上限（防解码炸弹）；
- **统一契约**：`{code, message, traceId, data}` 响应 + 错误码表 + X-Request-Id 全链路追踪；
- **可观测性**：liveness/readiness 探针、Prometheus 指标、启动自检预热、版本信息接口；
- **轻量鉴权**：可选 X-API-Key 白名单 + X-Caller 调用方归因日志；
- **调试台**：内嵌前端页面（上传/粘贴、旋转、档次切换、文字框可视化）。

## 环境要求

- JDK 17+，Maven 3.6+；Windows / Linux / macOS（OpenCV、ONNX Runtime 原生库由 Maven 自动拉取）

## 快速开始

```bash
mvn spring-boot:run
```

启动后访问 <http://localhost:8080/>（调试台），或直接调用接口。
本地默认 profile 为 dev（额外开放 `/api/ocr/demo` 演示接口）；生产部署显式指定
`--spring.profiles.active=prod` 即自动关闭演示接口。

模型：tiny 档随仓库提交开箱即用；small / medium 档需下载（见「模型档次」一节）。

## 接口总览

统一响应结构 `{code, message, traceId, data}`，`traceId` 与请求头 `X-Request-Id` 一致
（未传时服务端生成并回写响应头）。

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/ocr` | POST (multipart) | 同步识别；参数 `file`、`tier`、`rotate`、`autoRotate` |
| `/api/ocr/base64` | POST (JSON) | 同步识别；`{image, tier, rotate, autoRotate}`，image 支持 data URL |
| `/api/ocr/tasks` | POST (multipart) | 提交异步任务，参数同 `/api/ocr`，返回 `{taskId}` |
| `/api/ocr/tasks/base64` | POST (JSON) | 提交异步任务（base64 形态） |
| `/api/ocr/tasks/{taskId}` | GET | 查询任务：`status` = RUNNING / DONE / FAILED |
| `/api/ocr/tiers` | GET | 模型档次列表与可用状态 |
| `/api/ocr/info` | GET | 组件版本 / mica-ppocr 版本 / 各档模型状态 |
| `/api/ocr/demo` | GET | 识别自带测试图（仅 dev profile） |
| `/actuator/health/liveness`、`/readiness` | GET | 存活/就绪探针（就绪含默认档模型文件检查） |
| `/actuator/prometheus` | GET | Prometheus 指标 |

参数说明：`tier` = tiny/small/medium（缺省用默认档）；`rotate` = 0/90/180/270（识别前顺时针转正）；
`autoRotate` = true 时忽略 rotate，用 tiny 档四方向试探选优（约 4 倍 tiny 耗时）。

识别结果 `data` 结构：`{source, tier, count, costMs, fullText, results: [{text, score, box}]}`，
`box` 为文字框四顶点坐标（按阅读顺序），`fullText` 为按序换行拼接的整页文本。

示例：

```bash
curl -F "file=@test_images/1.png" -F "tier=small" -F "rotate=90" http://localhost:8080/api/ocr
```

```bash
curl -X POST -H "Content-Type: application/json" -d '{"image":"<base64>","tier":"small"}' http://localhost:8080/api/ocr/base64
```

## 错误码表

| code | HTTP | 含义 |
|------|------|------|
| 0 | 200 | 成功 |
| 1001 | 400 | 参数错误（缺参、rotate/tier 非法、任务不存在或已过期等） |
| 1002 | 400 | 图片解码失败 |
| 1003 | 400 | 图片过大（超出 `ocr.max-pixels` 或上传体积限制） |
| 1004 | 400 | 模型档次不可用（模型文件未下载） |
| 2001 | 429 | 识别并发超限，请稍后重试 |
| 2002 | 504 | 识别超时（注意：ONNX 推理不可中断，任务会在后台跑完并释放资源） |
| 4010 | 401 | 未授权（X-API-Key 缺失或无效） |
| 5000 | 500 | 内部错误（携带 traceId 联系组件维护方） |

## 组件配置（ocr.*）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `ocr.model-root` | `models/ppocr-v6` | 模型根目录（部署时可指向任意挂载路径） |
| `ocr.max-pixels` | 40000000 | 解码后像素数上限（≈6300×6300） |
| `ocr.concurrency` | 0 | 识别并发上限，0 = CPU 核数；超限立即返回 2001 |
| `ocr.timeout-ms` | 30000 | 同步接口识别超时（异步任务不受此限制） |
| `ocr.warmup-tiers` | `[tiny]` | 启动预热档次；模型缺失则启动失败（fail-fast） |
| `ocr.api-keys` | `[]` | 空 = 鉴权关闭；非空则校验 `/api/**` 的 `X-API-Key` 头 |
| `ocr.accelerator` | `cpu` | 推理加速器：cpu / auto / coreml / cuda（见「GPU 加速」一节） |
| `ocr.task.ttl-minutes` | 30 | 异步任务结果保留时长 |

引擎调参（`mica.ai.ppocr.*`，三档共享基底）见 [USAGE.md](USAGE.md)。

## 鉴权与调用方标识

内网可信定位下鉴权默认关闭。开启：

```yaml
ocr:
  api-keys: [key-for-system-a, key-for-system-b]
```

调用方建议携带 `X-Caller: <系统标识>` 头——会写入访问日志（`[traceId][caller]`），
用于「谁在用、用了多少」的容量归因。调试台页面收到 401 时会弹框输入 Key（存 localStorage）。

## GPU 加速

mica-ppocr 1.0.1 的 `prefer-accelerator` 存在缺陷（provider 只记日志、未应用到会话，任何平台
都是空操作），因此本项目内置了修复版引擎 `AcceleratedPPOcrV6Engine`，由 `ocr.accelerator` 控制：

- **NVIDIA CUDA**（Linux/Windows，CUDA 12.x + cuDNN 9）：`mvn -Pgpu package` 构建
  （切换 `onnxruntime_gpu` 依赖）+ 运行时 `--ocr.accelerator=cuda`；已完成构建链路，
  推理效果待 NVIDIA 真机压测确认；
- **macOS CoreML**：功能可用但实测反而大幅变慢（模型图分区过碎 + 动态尺寸反复编译），
  **不建议开启**，Mac 上保持 `cpu`——详见 [USAGE.md 第四节](USAGE.md#四gpu--加速器的真实情况)；
- 开启任何加速器后不再保证与 Python 参考实现 bit-exact；生效配置见 `/api/ocr/info`。

## 异步任务的限制

任务为**内存态、单实例语义**：结果保留 `ocr.task.ttl-minutes`（默认 30 分钟），重启即失；
多副本部署时查询需粘性路由到提交实例，否则需自行改造为外部存储。并发满时提交即返回 2001。

## 可观测性

- 就绪探针聚合了「默认档模型文件在位」检查，模型目录挂载丢失会转为 DOWN 摘除流量；
- 核心指标：`ocr_recognize_seconds`（tier/outcome 标签的耗时分布）、`ocr_rejected_total`（拒绝计数）；
- 每个请求的日志带 `[traceId][caller]`，`/api` 访问日志记录方法/路径/状态/耗时。

## 模型档次

| 档次 | det | rec | 字符表 | 定位 |
|------|-----|-----|--------|------|
| tiny | 1.7 MB | 4.3 MB | ~2855 字符 | 轻量优先，速度快（随仓库提交） |
| small | 9.4 MB | 20.2 MB | ~2855 字符 | 均衡，推荐默认 |
| medium | 59.2 MB | 73.0 MB | ~7180 字符 | 精度优先，覆盖生僻字（仓库中为 zip 需解压） |

下载 small / medium（来自 mica-ppocr 仓库 `models/` 目录）：

```bash
mkdir -p models/ppocr-v6/small && for f in det.onnx rec.onnx dict.txt; do curl -L "https://raw.githubusercontent.com/lets-mica/mica-ppocr/master/models/ppocr-v6/small/$f" -o "models/ppocr-v6/small/$f"; done
```

默认档由 `application.yml` 的 `mica.ai.ppocr.*-path` 决定（当前 tiny，随启动预加载）；
请求级切换用 `tier` 参数。

## 项目结构

```
pp_ocr4j/
├── pom.xml
├── models/ppocr-v6/tiny/                    # tiny 档模型（small/medium 不入库，见 .gitignore）
├── test_images/1.png                        # 集成回归测试用图
├── .github/workflows/ci.yml                 # CI：JDK17 + mvn package（含集成测试）
└── src/
    ├── main/java/com/example/ppocr4j/
    │   ├── config/                          # OcrProperties(ocr.*)、OcrTierCustomizer
    │   ├── exception/OcrException.java
    │   ├── health/OcrReadinessIndicator.java
    │   ├── service/                         # OcrService / OcrEngineManager / OcrExecutor / OcrWarmupRunner
    │   ├── task/OcrTaskManager.java         # 异步任务（内存态）
    │   └── web/                             # 控制器、ApiResult/ErrorCode、TraceId/ApiKey 过滤器
    ├── main/resources/
    │   ├── application.yml
    │   └── static/index.html                # 调试台
    └── test/java/                           # 单元测试 + tiny 引擎集成回归测试
```

## 测试

```bash
mvn test
```

集成回归测试用真实 tiny 引擎识别 `test_images/1.png` 并断言关键字段
（「中华人民共和国机动车行驶证」「京N99FF7」等）——升级 mica-ppocr 版本时的精度回归防线。

## 参数调优（USAGE.md）

详见 [USAGE.md](USAGE.md)，内容索引：

| 章节 | 内容 |
|------|------|
| [一、流水线与参数全景](USAGE.md#一流水线与参数全景) | 全部参数的默认值、所属阶段与语义（含不可配置的硬编码值） |
| [二、实测基准](USAGE.md#二实测基准先建立直觉) | 默认 / `max/960` / 多线程三种配置的耗时与质量对比 |
| [三、分场景推荐](USAGE.md#三分场景推荐) | 通用文档、拍照大图、密集小字、证照票据、模糊图、截图、高并发、Python 对拍等 8 类场景的 yml 配置 |
| [四、GPU / 加速器](USAGE.md#四gpu--加速器的真实情况) | `prefer-accelerator` 的真实行为与 CUDA 依赖替换 |
| [五、容易踩的坑](USAGE.md#五容易踩的坑) | `rec-image-shape` 陷阱、超长文本行、内存保护、调参先后顺序 |

## 相关链接

- mica-ppocr：<https://github.com/lets-mica/mica-ppocr>（Gitee: <https://gitee.com/dreamlu/mica-ppocr>）
- 许可证：Apache License 2.0
