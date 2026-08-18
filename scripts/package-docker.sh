#!/usr/bin/env bash
# 打包 Docker 镜像（CPU 版，linux/amd64）→ dist/pp-ocr4j-docker.tar.gz
# 适用：服务器 glibc < 2.28（如 CentOS 7）无法直接跑 jar，改用容器部署。
#
# 用法：
#   scripts/package-docker.sh              # mvn 构建 + docker build + docker save
#   scripts/package-docker.sh --skip-build # 复用 target/ 已有 jar
set -euo pipefail
cd "$(dirname "$0")/.."

IMAGE=pp-ocr4j:cpu

command -v docker >/dev/null || { echo "错误：本机未安装 docker" >&2; exit 1; }

if [[ "${1:-}" != "--skip-build" ]]; then
  echo "==> mvn 构建（默认 profile = CPU 版 onnxruntime）"
  mvn -B -DskipTests clean package
fi
ls target/pp-ocr4j-*.jar >/dev/null 2>&1 || { echo "错误：target/ 下没有 jar" >&2; exit 1; }

echo "==> docker build（固定 linux/amd64，Apple Silicon 上会走模拟，纯打包不慢）"
docker build --platform linux/amd64 -f deploy/Dockerfile -t "$IMAGE" .

mkdir -p dist
OUT=dist/pp-ocr4j-docker.tar.gz
echo "==> docker save → $OUT"
docker save "$IMAGE" | gzip > "$OUT"
echo "==> 完成：${OUT}（$(du -h "$OUT" | cut -f1)）"
echo "    下一步：scripts/deploy-docker.sh user@server"
