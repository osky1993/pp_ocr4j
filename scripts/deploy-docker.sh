#!/usr/bin/env bash
# Docker 方式一键部署：上传镜像包 → docker load → 重建容器 → 等待就绪
#
# 用法：
#   scripts/deploy-docker.sh user@server            # 使用已有 dist 镜像包
#   scripts/deploy-docker.sh user@server --package  # 先重新打包镜像再部署
#
# 要求：服务器已安装 docker 且远端用户有权限执行（root 或 docker 组）。
set -euo pipefail
cd "$(dirname "$0")/.."

TARGET=${1:-}
[[ -n "$TARGET" ]] || { echo "用法：scripts/deploy-docker.sh user@server [--package]" >&2; exit 1; }

PORT=${PORT:-8080}          # 宿主机映射端口，可 PORT=9090 scripts/deploy-docker.sh ... 覆盖
IMAGE=pp-ocr4j:cpu
NAME=pp-ocr4j

if [[ "${2:-}" == "--package" || ! -f dist/pp-ocr4j-docker.tar.gz ]]; then
  scripts/package-docker.sh
fi

echo "==> 上传镜像包（$(du -h dist/pp-ocr4j-docker.tar.gz | cut -f1)）"
scp dist/pp-ocr4j-docker.tar.gz "$TARGET:/tmp/"

echo "==> 远端加载镜像并重建容器"
ssh "$TARGET" bash -s <<EOF
set -euo pipefail
command -v docker >/dev/null || { echo "错误：服务器未安装 docker" >&2; exit 1; }
docker load < /tmp/pp-ocr4j-docker.tar.gz
docker rm -f $NAME 2>/dev/null || true
docker run -d --name $NAME --restart unless-stopped -p $PORT:8080 $IMAGE
rm -f /tmp/pp-ocr4j-docker.tar.gz

echo "==> 等待就绪（http://127.0.0.1:$PORT/actuator/health/readiness）..."
for i in \$(seq 1 60); do
  if curl -sf "http://127.0.0.1:$PORT/actuator/health/readiness" >/dev/null 2>&1; then
    echo "==> 部署成功。常用命令：docker logs -f $NAME / docker restart $NAME"
    exit 0
  fi
  sleep 2
done
echo "错误：120 秒内未就绪，请看日志：docker logs $NAME" >&2
exit 1
EOF
