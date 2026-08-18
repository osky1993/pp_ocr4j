#!/usr/bin/env bash
# 打包 CPU 版部署产物（Linux 服务器、无 GPU）
#
# 用法：
#   scripts/package.sh              # mvn 构建 + 组装 dist/pp-ocr4j-cpu.tar.gz
#   scripts/package.sh --skip-build # 复用 target/ 下已有 jar，只组装 tar 包
#
# 产物结构（解压后）：
#   pp-ocr4j/
#     pp-ocr4j.jar            Spring Boot fat jar（默认构建即 CPU 版 onnxruntime）
#     models/ppocr-v6/...     模型文件（打包本机已有的档次）
#     config/application-prod.yml   生产覆盖配置（外部配置，改后重启即生效）
#     bin/start.sh            前台启动（systemd 调用；也可手动运行）
#     pp-ocr4j.service        systemd 单元
#     pp-ocr4j.env            JVM / 环境变量配置
#     install.sh              服务器端安装脚本（root 运行）
set -euo pipefail
cd "$(dirname "$0")/.."

SKIP_BUILD=false
[[ "${1:-}" == "--skip-build" ]] && SKIP_BUILD=true

if ! $SKIP_BUILD; then
  echo "==> mvn 构建（默认 profile = CPU 版 onnxruntime，勿加 -Pgpu）"
  mvn -B -DskipTests clean package
fi

JAR=$(ls target/pp-ocr4j-*.jar 2>/dev/null | grep -v '\.original$' | head -1)
[[ -n "$JAR" ]] || { echo "错误：target/ 下未找到 fat jar，请先执行 mvn package" >&2; exit 1; }

STAGE=$(mktemp -d)/pp-ocr4j
trap 'rm -rf "$(dirname "$STAGE")"' EXIT
mkdir -p "$STAGE"/{bin,config}

cp "$JAR" "$STAGE/pp-ocr4j.jar"
cp deploy/application-prod.yml "$STAGE/config/"
cp deploy/start.sh             "$STAGE/bin/"
cp deploy/pp-ocr4j.service     "$STAGE/"
cp deploy/pp-ocr4j.env         "$STAGE/"
cp deploy/install.sh           "$STAGE/"
chmod +x "$STAGE/bin/start.sh" "$STAGE/install.sh"

echo "==> 打包模型目录（tiny 必需；small/medium 按本机是否存在）"
[[ -f models/ppocr-v6/tiny/det.onnx ]] || { echo "错误：models/ppocr-v6/tiny 不完整" >&2; exit 1; }
mkdir -p "$STAGE/models"
cp -R models/ppocr-v6 "$STAGE/models/"
for tier in small medium; do
  [[ -d models/ppocr-v6/$tier ]] || echo "  提示：本机没有 $tier 档模型，未打入（下载方式见 README）"
done

mkdir -p dist
OUT=dist/pp-ocr4j-cpu.tar.gz
tar -czf "$OUT" -C "$(dirname "$STAGE")" pp-ocr4j
echo "==> 完成：$OUT（$(du -h "$OUT" | cut -f1)）"
echo "    下一步：scripts/deploy.sh user@server 上传并安装"
