# 测试图片来源

| 文件 | 内容 | 来源 / 许可 |
|------|------|-------------|
| `1.png` | 机动车行驶证 | 仓库自带 |
| `passport-specimen.jpg` | 荷兰护照资料页 **SPECIMEN 样本** | [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Dutch_passport_specimen_issued_9_March_2014.jpg)，CC0 1.0 公有领域 |

`passport-specimen.jpg` 是荷兰身份服务局（RvIG）公开发布的官方样本，持照人
「Willeke Liselotte De Bruijn」为虚构人物，页面印有 SPECIMEN 字样，**不涉及任何真实个人信息**。
选它作为回归锚点的原因是机读区（MRZ）两行完整清晰，且五个校验位全部自洽，
可以对 `PassportParser` 的 MRZ 链路做强断言（见 `OcrIntegrationTest#parsesPassportMrzFields`）。

> 请勿向本目录提交任何真实证件图片。护照解析器的可视区分支（中文标签定位）
> 由 `PassportParserTest` 用构造出的文本框覆盖，不需要真实中国护照样图。

## fixtures/ 固定测试集

`fixtures/*.json` 是质量门禁（`scripts/quality-gate.sh`）的输入：每个文件描述一个用例——
用哪张图、走哪个 docType、期望解析出什么字段。

```json
{
  "case": "用例名（基线以此为 key）",
  "docType": "passport",
  "image": "passport-specimen.jpg",   // 相对 test_images/
  "tier": "tiny",
  "critical": ["mrzValid", "passportNo"],   // 这些字段必须 100% 正确
  "expected": { "passportNo": "SPECI2014", "nameCn": null }
}
```

两条约定：

- **`expected` 里写 `null` 是有意义的断言**，表示「该字段就该是空的」。护照用例里
  `surname`/`givenNames` 期望为 `null` 不是缺陷——tiny 档会把 MRZ 姓名区的 `<<` 少读一个，
  解析器按设计拒绝猜姓名边界（猜错会把复姓切错）。假阳率指标（G3c）正是基于这些字段统计的。
- **期望值必须来自实测并经人工确认**，不能凭空写。基线一旦定下，后续只允许升不允许降。
