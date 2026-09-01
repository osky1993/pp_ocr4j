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
