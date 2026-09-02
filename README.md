# pp-ocr4j

独立运行的 OCR 组件：基于 [mica-ppocr](https://github.com/lets-mica/mica-ppocr)
（PP-OCRv6 的 Java 17 原生实现，纯 ONNX Runtime 本地推理，Apache 2.0），
以 Spring Boot 3.x 服务形态对内网系统提供文字识别能力。数据不出内网，零 Python 依赖。

> 起源：参考文章《Java 首款 PP-OCR 原生库：零 Python 依赖，Maven 引入即用》搭建的示例项目，
> 现已按「内部可信接口调用的独立组件」定位补齐工程化能力。
>
> 注意：文章中依赖坐标写的是 `net.dreamlu.mica.ai`，Maven Central 上实际发布的 groupId 是
> **`net.dreamlu`**（本项目版本 `1.1.3`）；`PPOcrV6Result` 实际位于
> `net.dreamlu.mica.ai.ppocr.engine` 包（文章写的 `config` 包有误）。

## 能力一览

- **三档模型**（tiny/small/medium）请求级动态切换，按档懒加载、共享调参基底；
- **同步 / 异步**识别接口，multipart 与 base64 双输入形态；
- **方向自动转正**：1.1.1+ 官方文档方向分类（doc_ori 模型，本项目默认开启）在检测前自动把
  横拍/倒置图转正，结果的 `rotatedDegrees` 记录应用角度；`rotate` / `autoRotate` 参数保留
  用于关闭 doc_ori 或需要显式控制的场景；
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
| `/api/ocr/parse/{docType}` | POST (multipart) | 证件/票据字段级结构化提取；参数同 `/api/ocr` |
| `/api/ocr/parse/{docType}/base64` | POST (JSON) | 结构化提取（base64 形态），请求体同 `/api/ocr/base64` |
| `/api/ocr/parse/types` | GET | 支持的证件类型列表 |
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

> 服务端已默认开启官方文档方向分类（`mica.ai.ppocr.use-doc-orientation-classify`），
> 横拍/倒置图会自动转正（返回结果的 `rotatedDegrees` 记录角度，此时 `box` 坐标基于转正后的图像），
> 一般无需再传 `rotate` / `autoRotate`。`autoRotate` 与 doc_ori 同时开启时为重复工作，不建议。

识别结果 `data` 结构：`{source, tier, count, costMs, fullText, results: [{text, score, box, rotatedDegrees}]}`，
`box` 为文字框四顶点坐标（按阅读顺序），`fullText` 为按序换行拼接的整页文本。

### 字段级结构化提取

前六种基于 mica-ppocr-structured 1.1.x 内置解析器，`passport` 为本项目自建（见 [`parser/PassportParser`](src/main/java/com/example/ppocr4j/parser/PassportParser.java)）。
`docType` 支持（大小写、`-`/`_`/驼峰写法均兼容）：

| docType | 证件 | 提取字段 |
|---------|------|----------|
| `vehicle-license` | 行驶证 | plateNo, owner, vehicleType, vin, issueDate |
| `id-card` | 身份证 | side(FRONT/BACK), name, gender, nation, birthDate, address, idNumber, issuingAuthority, validFrom, validTo |
| `bank-card` | 银行卡 | cardNumber, validDate, holderName, bankName, cardType |
| `driver-license` | 驾驶证 | licenseNumber, name, gender, nationality, address, birthDate, issueDate, vehicleClass, issuingAuthority, validFrom, validTo |
| `business-license` | 营业执照 | creditCode, name, type, legalPerson, registeredCapital, establishDate, operatingPeriod, address, businessScope |
| `invoice` | 增值税发票 | invoiceCode, invoiceNo, invoiceDate, 买方/卖方名称·税号·地址电话·开户行, goodsName, amount, taxRate, taxAmount, totalAmountUpper/Lower, payee, reviewer, issuer |
| `passport` | 护照 | mrzLine1/2, mrzValid, documentType, issuingCountry, passportNo, nationality, nameEn, surname, givenNames, sex, birthDate, expiryDate, personalNumber, nameCn, placeOfBirth, placeOfIssue, issueDate, authority |
| `hk-macao-permit` | 往来港澳通行证 | mrzLine, mrzValid, documentType, permitNo, birthDate, validFrom, expiryDate, nameCn, nameEn, sex, issuingAuthority, placeOfIssue |

`passport` 走 **MRZ 优先、可视区兜底** 策略：先解资料页底部两行 44 字符机读区
（ICAO 9303 TD3，定长定位且自带校验位），再用中英双语标签补齐 MRZ 里没有的字段
（中文姓名、出生地点、签发地点、签发机关）。`mrzValid=true` 表示护照号/出生日期/有效期/
个人号/综合五个校验位全部自洽，字段可直接采信；`false` 表示至少一位不匹配（OCR 误识或图片模糊），
字段仍返回但需人工复核；`null` 表示没找到机读区，全部字段来自可视区。
注意 OCR 可能把姓名区的 `<<` 分隔符少读一个，此时 `surname`/`givenNames` 为 null（不猜边界以免切错复姓），
请改用 `nameEn`。

`hk-macao-permit`（往来港澳通行证）走的是**中国出入境证件自有的单行 30 字符机读码**，
印在卡片正面底部——注意它**不是** ICAO TD1：卡片虽按 ICAO DOC 9303 TD-1 的物理尺寸
（85.6×54mm）制作，但机读码布局与 TD1 的 3 行 × 30 完全不同，背面则是签注区而非机读区。
布局与四个校验位由公安部公开的证件样本实测确认（校验位算法与 ICAO 的 7-3-1 加权模 10 相同）：

```
[0,2) 标识 CS │ [2,11) 证件号 [11]校验位 │ [13,19) 有效期 [19]校验位
              │ [21,27) 出生日期 [27]校验位 │ [29] 综合校验位
```

机读码**不含**姓名、性别、签发机关、有效期起始日，这些只能从可视区取，可靠性低于机读码字段。

> **同类证件的现状**：往来台湾通行证号码为 `L`/`T`+8 位，同为 9 位、同一发证体系，
> 疑似同版式但**未取得样图验证**；台湾居民来往大陆通行证（台胞证）号码 8 位、机读码标识
> 为 `CT`，字段位置因号码长度不同而**必然不同**；外国人永久居留身份证的机读码结构无公开
> 权威资料。这三种**均未实现**——在版式未经验证的情况下写解析器，产出的是看起来合理的
> 错值，比不支持更糟。取得样图后，往来台湾通行证可复用 `CN_EEP_9` 版式常量低成本扩展。

### 字段校验（validations）

响应里除 `fields` 外还有一个 `validations`，给出字段的**自校验结论**——只做判断，
不改动 `fields` 里的值：

```json
"validations": {
  "idNumber":   {"valid": true,  "rule": "ISO 7064 MOD 11-2（GB 11643）", "note": null},
  "creditCode": {"valid": false, "rule": "GB 32100-2015",
                 "note": "含 GB 32100 排除的字母 I/O/S/V/Z，疑似把 1/0/5/U/2 读错"}
}
```

| docType | 字段 | 规则 |
|---|---|---|
| `id-card` | idNumber | ISO 7064 MOD 11-2 校验位 + 出生日期段合法性 |
| `driver-license` | licenseNumber | 同上（驾驶证号在中国就是身份证号） |
| `business-license` | creditCode | GB 32100-2015 校验位 + 31 字符集 |
| `invoice` | buyerTaxNo / sellerTaxNo | GB 32100-2015 |
| `invoice` | totalAmountUpper / totalAmountLower | 大小写金额一致 + 金额 + 税额 = 价税合计 |
| `bank-card` | cardNumber | Luhn（ISO/IEC 7812-1） |
| `passport` | personalNumber | 仅当 `issuingCountry` 为 `CHN` 时按身份证规则校验 |

只覆盖有校验规则的字段，其余字段不会出现在 `validations` 里（不是 `valid: true`）。
`passport` 与 `hk-macao-permit` 的机读码另有独立的 `mrzValid` 字段。

**为什么需要这一层**：上游 mica-ppocr-structured 的六个内置解析器全部是「标签定位 + 裸正则」，
没有任何一处校验位验证。OCR 把身份证号里的 `0` 读成 `8`、把信用代码里的 `1` 读成 `I`，
解析器都会照单全收，调用方拿到一个格式完全正确但内容错误的号码。

> **能力边界**：校验是**后处理**（上游解析器在 jar 里改不了），因此只能发现问题、
> 无法修复上游产生的错值。最典型的是驾驶证号——上游正则 `\d{15,18}` 不接受末位 X，
> 末位为 X 的证号（约 1/11 概率）会被截断成 17 位数字返回。`validations` 能认出这个特征
> 并提示「疑似末位 X 被截断」，但拿不回那个 X。

新增自定义解析器只需三步：继承 `BaseStructuredParser<R>` 实现 `parseResults(List)`、
结果类继承 `BaseStructuredResult`、在 `OcrParseService` 构造函数的 Map 里注册一行——
docType 归一化、types 接口、fieldBoxes 与 Base64 接口都会自动生效。

```bash
curl -F "file=@行驶证.jpg" -F "tier=small" http://localhost:8080/api/ocr/parse/vehicle-license
```

返回 `data`：`{source, docType, tier, costMs, fields, fieldBoxes, results}`——`fields` 为业务字段
（未识别到的字段为 null，建议业务侧对关键字段做非空与格式校验）；`fieldBoxes` 为字段名 → 命中
文本框坐标（基于转正后图像），供可视化定位；`results` 为 OCR 原始逐行结果，供审计与兜底。
识别底座与 `/api/ocr` 完全一致：tier/rotate/autoRotate、doc_ori 自动转正、并发闸门与错误码通用。

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

## Linux 生产部署（CPU）

无 GPU 服务器用默认构建即可（CPU 版 onnxruntime，`ocr.accelerator: cpu`），
onnxruntime 与 OpenCV 的 jar 内置 linux-x64 原生库，Mac 上打的包可直接部署。
服务器只需 **JDK/JRE 17+**，无需装任何原生依赖。

**glibc 前置要求**：onnxruntime ≥1.17 的官方 Linux 库基于 manylinux_2_28 构建，
要求 **glibc ≥ 2.28**（Ubuntu 20.04+ / Debian 11+ / Rocky·Alma 8+）。CentOS 7 / RHEL 7
（glibc 2.17）直接跑 jar 会报 `GLIBC_2.27' not found`——这类旧系统请走下方 Docker 路线
（容器自带新 glibc，宿主机只需 Docker）。

### 方式一：systemd 直跑（glibc ≥ 2.28 的系统）

```bash
# 本机：构建 fat jar + 模型 + 配置 + systemd 单元 → dist/pp-ocr4j-cpu.tar.gz
scripts/package.sh

# 一键部署：上传到服务器并远端执行 install.sh（需 sudo）
scripts/deploy.sh user@server
```

### 方式二：Docker（旧系统适用，如 CentOS 7）

```bash
# 本机：mvn 构建 + docker build（固定 linux/amd64）+ docker save → dist/pp-ocr4j-docker.tar.gz
scripts/package-docker.sh

# 一键部署：上传镜像包 → docker load → 重建容器（--restart unless-stopped）→ 等待就绪
scripts/deploy-docker.sh user@server        # 端口可用 PORT=9090 前缀覆盖，默认 8080
```

镜像内置模型与生产配置（[deploy/Dockerfile](deploy/Dockerfile)），需要改配置时可
`-v /path/application-prod.yml:/app/config/application-prod.yml` 挂载覆盖，
JVM 参数用 `-e JAVA_OPTS=...` 覆盖。

服务器端安装到 `/opt/pp-ocr4j`，以系统用户 `ppocr` 运行 systemd 服务 `pp-ocr4j`，
安装脚本会等待 `/actuator/health/readiness` 就绪后才报成功。生产配置在
`/opt/pp-ocr4j/config/application-prod.yml`（升级不覆盖），JVM 参数在
`/opt/pp-ocr4j/pp-ocr4j.env`。生产以 `prod` profile 运行，自动关闭 `/api/ocr/demo`；
建议部署后配置 `ocr.api-keys` 开启鉴权。

```bash
# 服务器上常用命令
systemctl status pp-ocr4j
journalctl -u pp-ocr4j -f
```

## GPU 加速

mica-ppocr 1.1.3 的 `prefer-accelerator` 仍存在缺陷（provider 只记日志、未应用到会话，任何平台
都是空操作），因此本项目内置了修复版引擎 `AcceleratedPPOcrV6Engine`，由 `ocr.accelerator` 控制：

- **NVIDIA CUDA**（Linux/Windows，CUDA 11.8 + cuDNN 8，对应 ORT 1.18）：`mvn -Pgpu package` 构建
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
├── quality-baseline.json                    # 质量门禁基线（准确率/耗时，只升不降）
├── models/ppocr-v6/tiny/                    # tiny 档模型（small/medium 不入库，见 .gitignore）
├── models/ppocr-v6/doc_ori/doc_ori.onnx     # 文档方向分类模型（默认开启，三档共享，随仓库提交）
├── test_images/                             # 测试图与固定测试集（来源与许可见目录内 README）
│   ├── 1.png                                #   行驶证，集成回归 + 性能基准用图
│   ├── passport-specimen.jpg                #   荷兰护照 SPECIMEN（CC0）
│   ├── hk-macao-permit-specimen.jpg         #   往来港澳通行证官方样本（PD）
│   └── fixtures/*.json                      #   门禁用例：图 + docType + 期望字段 + critical 字段
├── scripts/
│   ├── quality-gate.sh                      # 质量门禁执行器（G1~G7）
│   ├── quality_check.py                     #   字段准确率计算与基线比对
│   └── package*.sh / deploy*.sh             # 打包与部署
├── .github/workflows/ci.yml                 # CI：JDK17 + mvn package（含集成测试）
└── src/
    ├── main/java/com/example/ppocr4j/
    │   ├── config/                          # OcrProperties(ocr.*)、OcrTierCustomizer
    │   ├── exception/OcrException.java
    │   ├── health/OcrReadinessIndicator.java
    │   ├── parser/                          # 本项目自建的结构化解析器
    │   │   ├── PassportParser + Result      #   护照（ICAO TD3）
    │   │   ├── HkMacaoPermitParser + Result #   往来港澳通行证（中国出入境单行机读码）
    │   │   ├── mrz/                         #   MRZ 引擎：版式数据表 + 定位 + 校验位 + 日期
    │   │   └── validate/                    #   字段自校验：校验位算法、大写金额、按 docType 分派
    │   ├── service/                         # OcrService / OcrEngineManager / OcrExecutor / OcrWarmupRunner
    │   │   └── OcrParseService              #   docType 注册表 + 解析 + 后处理校验
    │   ├── task/OcrTaskManager.java         # 异步任务（内存态）
    │   └── web/                             # 控制器、ApiResult/ErrorCode、TraceId/ApiKey 过滤器
    ├── main/resources/
    │   ├── application.yml
    │   └── static/index.html                # 调试台
    └── test/java/com/example/ppocr4j/
        ├── contract/                        # 门禁 G6/G7：Bean 契约、文档与 fixture 同步
        ├── parser/                          # 解析器与 MRZ/校验组件单元测试
        ├── service/ · web/                  # 并发原语、请求解码
        └── OcrIntegrationTest.java          # tiny 引擎真实推理的端到端回归
```

## 测试

```bash
mvn test
```

集成回归测试用真实 tiny 引擎识别 `test_images/1.png` 并断言关键字段
（「中华人民共和国机动车行驶证」「京N99FF7」等）——升级 mica-ppocr 版本时的精度回归防线。

## 质量门禁

新增证件类型、升级 mica-ppocr、改动解析器之后，提交前跑一次：

```bash
scripts/quality-gate.sh --update-baseline
```

门禁不改动 `pom.xml` 与 CI，全部由脚本 + 基线文件承载。对 OCR 结构化服务而言
**字段准确率才是真实质量指标**——行覆盖率再高，抽错字段的解析器也没有价值。

| 门禁 | 内容 | 阈值 |
|------|------|------|
| G1 | 测试全绿 | 零 Failures / Errors / Skipped |
| G2 | 测试数不减少 | ≥ 基线（防止删测试换绿灯） |
| G3a | 字段准确率不回退 | 已有 docType 只升不降；新增 ≥ 90% |
| G3b | 关键字段零错 | `critical` 字段 100% |
| G3c | 假阳率 | ≤ 5% |
| G4 | 性能不劣化 | tiny 档耗时 ≤ 基线 × 1.2（告警级） |
| G5 | 无真实证件入库 | `test_images/` 新增图必须在 README 登记来源与许可 |
| G6 | Bean 契约 | 每个结果类字段都有可读 getter |
| G7 | 文档同步 | README 类型表、演示页下拉框、fixture 覆盖全部 docType |

**G3c 假阳率为什么单列**：准确率一个数字掩盖了两种严重程度截然不同的失败。漏字段
（返回 null）调用方看得见；**填错值调用方看不见**——拿到一个看起来合理的错值比拿到 null
危险得多。护照解析器开发时就实测踩到过这个（`sex` 一度被填成隔壁的 `国籍/Nationality`
标签文本），必须有独立指标守住。

固定测试集在 [`test_images/fixtures/`](test_images/fixtures/)，基线在 `quality-baseline.json`。
基线**只升不降**：若某次迭代确实需要放宽，必须在 commit message 里用 `Accuracy-Change:`
尾注写明原因——把「放宽标准」变成显式决策而不是悄悄发生。

> **已知缺口**：上游内置的 `id-card` / `bank-card` / `driver-license` / `business-license`
> / `invoice` 五种类型**没有准确率覆盖**，因为没有合规样图（真实证件禁止入库）。
> 这一事实在 `DocsSyncTest.KNOWN_UNCOVERED` 里显式登记，取得合规样图后应补 fixture 并移除。

## 扩展路线图与已知缺口

结构化解析已经过四轮迭代（质量门禁 → MRZ 引擎 → 港澳通行证 → 字段校验）。
以下是**明确已知、尚未完成**的部分，记录在此以便接续。

### 可低成本扩展（版式已知，缺样图）

| 证件 | 已知信息 | 接续做法 |
|------|---------|---------|
| 往来台湾通行证 | 号码 `L`/`T`+8 位，共 9 位，同一发证体系 | 疑似复用 `MrzFormat.CN_EEP_9`，取得样图实测确认标识码与字段位置后即可加解析器 |
| 台湾居民来往大陆通行证 | 号码 8 位，机读码标识 `CT` | 号码长度不同 → 字段位置必然不同，需**新立版式常量**，不能套用 `CN_EEP_9` |
| 外国人永久居留身份证 | 无 | 机读码结构无公开权威资料，需官方文档或样图 |

**不要在版式未经验证的情况下先写解析器**——产出的是看起来合理的错值，比不支持更糟。
护照与港澳通行证两次的经验都表明：必须先打 `/api/ocr` 看原始识别文本，再据此写解析器。
凭想象写的正则，实测时几乎总是错的（`<<` 会被漏读、空格会被吃掉导致日月粘连、
标签会被读成错字）。

### 已放弃（技术前提消失）

铁路电子客票报销凭证、航空运输电子客票行程单——原计划做 OCR 解析，检索后放弃：

- 铁路：2024-11-01 起推广数电票，纸质报销凭证 2025 年 9 月底已全面退场
- 航空：2025-10-01 起国内航班完全停止打印纸质行程单

两者现在都是数电票（PDF / OFD），**本身带结构化数据**。对这类文件用 OCR 是错误的
技术选择——直接解析 OFD / XML 能拿到 100% 准确的字段，OCR 再准也是在猜。
如果确有需求，应该做的是 OFD 解析（另一个技术栈），或者针对「用户提交的数电票截图」
这一特定场景做 OCR。

### 准确率覆盖缺口

上游内置的 `id-card` / `bank-card` / `driver-license` / `business-license` / `invoice`
五种类型**没有字段准确率覆盖**，因为没有合规样图（真实证件禁止入库）。这一事实显式登记在
`DocsSyncTest.KNOWN_UNCOVERED`，并有一条测试防止豁免清单留下僵尸条目。
取得合规样图后应补 fixture 并从清单移除，以恢复门禁保护。

这五种类型现在有 `validations` 字段级校验（校验位、金额勾稽），但那是**结果自校验**，
不能替代准确率基准——校验位只能发现「值是错的」，发现不了「字段抓错了位置」。

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
