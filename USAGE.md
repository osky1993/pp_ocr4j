# 使用建议：mica-ppocr 参数调优指南

> 基于 mica-ppocr 1.0.1 源码（含 sources jar）逐行核对，默认值为 `PPOcrV6Config.defaults()`
> 实测输出；性能数据实测于 Apple Silicon（4 性能核 + 6 能效核），测试图为
> `test_images/1.png`（2988×2199 行驶证照片），热身后取稳定值。

## 一、流水线与参数全景

识别流水线：**检测缩放 → det 推理 → DB 后处理出框 → 排序 → 透视裁剪 → rec 批量推理 → CTC 解码**。
参数分四组，作用在不同阶段：

| 参数 | 默认值 | 阶段 | 语义 |
|------|--------|------|------|
| `det-limit-side-len` | 64 | 检测缩放 | 配合 limit-type 的边长阈值 |
| `det-limit-type` | `min` | 检测缩放 | `min`：短边 < 阈值才放大（≈保持原分辨率）；`max`：长边 > 阈值则整体缩小到阈值 |
| `det-max-side-limit` | 4000 | 检测缩放 | 长边硬上限（内存/耗时保护），缩放结果再对齐到 32 的倍数 |
| `det-thresh` | 0.3 | DB 后处理 | 概率图二值化的**像素级**门槛，决定弱笔画能否成区域 |
| `det-box-thresh` | 0.6 | DB 后处理 | 候选框内平均概率的**框级**门槛，决定低置信区域去留 |
| `det-unclip-ratio` | 1.5 | DB 后处理 | 文字框向外扩张比例：大 → 框宽松；小 → 框紧凑 |
| `rec-image-shape` | [3,48,320] | 识别预处理 | **只有 H=48 实际生效**（源码中宽度 320~3200 硬编码），须与模型匹配，勿改 |
| `rec-batch-size` | 6 | 识别推理 | 每次 ONNX 推理喂入的文本行数，只影响性能不影响结果 |
| `intra-op-num-threads` | 1 | ONNX Runtime | 单个算子内部并行线程数——CPU 上最主要的提速旋钮 |
| `inter-op-num-threads` | 1 | ONNX Runtime | 算子间并行，OCR 模型是顺序图，**保持 1 即可** |
| `prefer-accelerator` | false | ONNX Runtime | true 时按 CoreML > CUDA > CPU 自动选择（见第四节的重要限制） |

源码中还有两个**不可配置**的固定值：单图最多候选框 `maxCandidates=1000`、最小框边长
`minSize=3px`；识别行宽上限 3200px（超长文本行会被压扁，见第五节）。

## 二、实测基准（先建立直觉）

同一张 2988×2199 照片，三种配置的单张耗时与检出框数：

| 配置 | tiny | small | medium | 说明 |
|------|------|-------|--------|------|
| 默认（min/64，单线程） | ~1.0s / 36 框 | ~2.8s / 37 框 | ~14s / 37 框 | 检测按原分辨率跑 |
| `max/960`（单线程） | ~0.33s / 35 框 | ~1.4s / 36 框 | ~6.4s / 39 框 | 检测按长边 960 跑 |
| 默认 + `intra-op=8` | ~0.7s / 36 框 | ~1.5s / 37 框 | ~7.0s / 37 框 | 框数与单线程完全一致 |

两个关键结论：

1. **检测耗时 ≈ 正比于像素数**。默认 `min/64` 对大图几乎不缩放（长边 4000 以内原样跑），
   这是「高保真但慢」路线；`max/960` 把 3000px 照片砍到 960px，速度 2~3 倍，
   但小字/低对比字段开始劣化——实测中主体字段（车牌、姓名、证号）全部保持正确，
   而图片底部**小号浅色的英文标签**从 0.97+ 置信度跌到 0.69~0.77 并出现字符错误
   （`RegisterDate` → `Reg1s1erDale`）。这就是这组参数的典型代价。
2. **`intra-op` 多线程提速 1.5~2 倍且结果与单线程逐框一致**（同机同参数下确定性保持），
   代价是放弃官方承诺的「跨平台 bit-exact」与更高的 CPU 占用。

## 三、分场景推荐

以下 yml 片段都写在 `mica.ai.ppocr` 下；本项目中这些调参是三档共享的基底
（`OcrEngineManager` 会继承），档次本身用前端/`tier` 参数选。

### 1. 通用文档、扫描件（推荐基线）

扫描件分辨率可控、字号正常，默认参数就是为这类场景校准的：

```yaml
# 全部默认即可；档次选 small（均衡）
```

### 2. 手机拍照 / 高分辨率大图，速度优先

3000~4000px 的拍照图默认会按原分辨率检测，最慢。若业务字号较大（标题、证件主字段），
用 `max` 模式换速度：

```yaml
det-limit-type: max
det-limit-side-len: 960     # 速度 2~3 倍；小字多就升到 1280/1600 折中
```

判断标准：缩放后关键文字的**字高不低于 10~12px**。以 4000px 宽、字高 40px 的原图为例，
960 配置下字高只剩 ~10px，处于临界；1280 则有 ~13px，较稳。

### 3. 密集小字：表格、合同、报纸、说明书

小字最怕降分辨率，保持默认 `min` 模式；问题通常出在「漏检」和「相邻行粘连」：

```yaml
# det-limit-type 保持默认 min（原分辨率）
det-box-thresh: 0.45        # 漏检首先降它（0.4~0.5），让低置信小字框保留
det-thresh: 0.25            # 仍漏再降像素门槛（别低于 0.2，噪声会连片）
det-unclip-ratio: 1.3       # 行距小时收紧外扩，防止上下行粘成一个框
rec-batch-size: 24          # 行多，加大批次提升吞吐（纯性能项）
```

档次选 medium（检测/识别精度最高，字符表 ~7180 覆盖生僻字）。

### 4. 证照、票据（结构化字段提取）

字段少而关键，错一个字就有业务代价——用精度换一切：

```yaml
# 保持默认 min 原分辨率；det-unclip-ratio 按症状微调：
det-unclip-ratio: 1.7       # 仅当字段边缘笔画被框截断时加大（默认 1.5 通常够）
```

- 档次用 small 起步，出现生僻字/易混字错误升 medium；
- 在业务层按 `score` 设复核线：`score < 0.85` 的字段转人工，
  比调低阈值硬识别更可靠；
- 固定版式的证件建议先按模板裁剪 ROI 再送识别，比全图调参收益大得多。

### 5. 模糊、弱光、低质量图片

笔画概率整体偏低，把两级门槛都放宽，框也放松：

```yaml
det-thresh: 0.2
det-box-thresh: 0.35
det-unclip-ratio: 1.8
```

档次必须 medium；代价是误检增多，务必在业务层丢弃 `score < 0.5` 的结果。
（识别质量上限取决于图片本身，参数只能兜底；比调参更有效的是前置图像增强。）

### 6. 屏幕截图 / UI 文案

数字生成的文字清晰无噪，是最容易的场景，追求快：

```yaml
det-limit-type: max
det-limit-side-len: 960
```

档次 tiny 足够（实测 0.3s 级）。

### 7. 高并发 / 实时服务

`PPOcrV6Engine.run()` 无锁，`OrtSession` 本身线程安全，多请求可以**并发共享同一引擎**。
两种策略：

```yaml
# A. 吞吐优先（QPS 高）：保持单线程，靠请求并发吃满核
intra-op-num-threads: 1     # 默认；总吞吐最优，且保持确定性

# B. 延迟优先（QPS 低、单张要快）：给单请求多线程
intra-op-num-threads: 4     # 建议 = 性能核数；实测提速 1.5~2 倍
```

B 策略在高并发时会争核导致长尾，建议配合信号量把并发识别数限制在
`核数 / intra-op` 附近。`inter-op` 任何场景都保持 1。

### 8. 与 Python 参考实现对拍 / 回归测试

**全部默认，一个都别动。** bit-exact 承诺的前提是 CPU 单线程 + 默认参数；
改线程数、Provider 或任何阈值后，结果只保证「同机同参数可复现」。

## 四、GPU / 加速器的真实情况

实测与源码核查结论（mica-ppocr 1.0.1 + ONNX Runtime 1.26.0）：

1. **上游 `prefer-accelerator` 是"假开关"**：`PPOcrV6Engine` 只把 `OrtProviders.resolve()`
   选出的 provider 写进日志，**从未调用 `SessionOptions.addCoreML()/addCUDA()`**，
   推理会话永远走 CPU。实测开关前后耗时完全一致，且日志会误导性地打出
   `CoreMLExecutionProvider`。（已整理 issue 反馈上游。）
2. 因此本项目内置 **`AcceleratedPPOcrV6Engine`**（逐行移植原版引擎 + 修复 EP 应用），
   由 `ocr.accelerator: cpu | auto | coreml | cuda` 控制；`cpu`（默认）走库原版引擎，
   保持 bit-exact。生效配置可查 `/api/ocr/info` 的 `accelerator` 字段。
3. **macOS CoreML：能跑通但反而大幅变慢，不建议开启**。Apple Silicon 实测
   （2988×2199 行驶证图）：tiny 3.5s（CPU 1.0s）、small 24.9s（CPU 2.8s）、medium 超 30s
   超时。ORT 日志给出了原因：det/rec 模型图被切成 19~29 个分区（190 节点中 169 个受支持），
   每个分区边界都要 CPU↔ANE 数据搬运，叠加 OCR 动态输入尺寸导致反复编译——
   动态形状模型上 CoreML 的典型劣化模式。
   （顺带证伪一点：ORT 1.26 Java 在 macOS 实际可枚举出 CORE_ML provider，
   上游文档「Java API 没有 CoreML」的说法已过时。）
4. **NVIDIA CUDA：构建路径已就绪，待真机验证**。步骤：

   ```bash
   mvn -Pgpu -DskipTests package   # 换用 onnxruntime_gpu 依赖（Linux/Windows）
   java -jar target/pp-ocr4j-*.jar --ocr.accelerator=cuda
   ```

   环境要求：NVIDIA GPU + CUDA 12.x + cuDNN 9（对应 ORT 1.26）。CUDA EP 对动态形状
   远比 CoreML 友好，预期 medium 档收益最大；上线前务必用业务图片对比精度与耗时。
5. 开启任何加速器都会**放弃 bit-exact 保证**；对拍/回归场景保持 `cpu`。

Apple Silicon 上的现实提速手段仍然是：`intra-op` 多线程 + 降分辨率 + tiny 档（见第二、三节）。

## 五、容易踩的坑

1. **`rec-image-shape` 不要动**：宽度分量在源码中根本不被使用（wMin=320、wMax=3200
   硬编码），高度 48 必须匹配模型，改了直接精度崩塌。
2. **超长文本行**：行宽上限 3200px，长宽比超过 ~66:1 的行会被压扁。整行超长的表格
   建议业务侧先垂直切分。
3. **`det-max-side-limit: 4000` 是内存保护**：det 输入为 float32，4000px 长边的张量
   单图约 100~190MB 峰值，多档并发时注意堆外内存。
4. **本项目中 `enabled: false` 不可用**：`OcrEngineManager` 依赖 Starter 装配的 Bean。
5. **调参先后顺序**：漏检 → 先 `det-box-thresh` ↓，再 `det-thresh` ↓；误检多 →
   `det-box-thresh` ↑；框截断笔画 → `det-unclip-ratio` ↑；相邻行粘连 →
   `det-unclip-ratio` ↓。一次只动一个，用固定测试集对比框数与字段正确率。
