#!/usr/bin/env bash
# 服务器端安装/升级脚本（在解压后的 pp-ocr4j/ 目录内以 root 运行）
#   sudo bash install.sh
#
# 行为：
#   - 校验 Java 17+
#   - 创建系统用户 ppocr 与安装目录 /opt/pp-ocr4j
#   - 复制 jar / 模型 / 脚本；config 与 env 仅首次安装时复制（升级不覆盖线上配置）
#   - 安装并启动 systemd 服务，等待就绪探针通过
set -euo pipefail
cd "$(dirname "$0")"

APP_DIR=/opt/pp-ocr4j
SERVICE=pp-ocr4j

[[ $EUID -eq 0 ]] || { echo "请用 root 运行：sudo bash install.sh" >&2; exit 1; }

# ---- Java 17+ 校验 ----
if ! command -v java >/dev/null; then
  echo "错误：未找到 java。请安装 JDK 17+，例如：" >&2
  echo "  Ubuntu/Debian: apt install -y openjdk-17-jre-headless" >&2
  echo "  RHEL/CentOS:   yum install -y java-17-openjdk-headless" >&2
  exit 1
fi
JAVA_MAJOR=$(java -version 2>&1 | awk -F'"' '/version/{split($2,v,"."); print v[1]}')
[[ "$JAVA_MAJOR" -ge 17 ]] || { echo "错误：需要 Java 17+，当前为 $JAVA_MAJOR" >&2; exit 1; }

# ---- 用户与目录 ----
id ppocr &>/dev/null || useradd --system --home-dir "$APP_DIR" --shell /usr/sbin/nologin ppocr
mkdir -p "$APP_DIR"

# ---- 停旧服务（升级场景）----
systemctl stop "$SERVICE" 2>/dev/null || true

# ---- 复制文件 ----
cp pp-ocr4j.jar "$APP_DIR/"
mkdir -p "$APP_DIR/bin"
cp bin/start.sh "$APP_DIR/bin/" && chmod +x "$APP_DIR/bin/start.sh"
rm -rf "$APP_DIR/models"
cp -R models "$APP_DIR/"
# 配置只在首次安装时落地，升级不覆盖线上已改配置
if [[ ! -f "$APP_DIR/config/application-prod.yml" ]]; then
  mkdir -p "$APP_DIR/config"
  cp config/application-prod.yml "$APP_DIR/config/"
fi
[[ -f "$APP_DIR/pp-ocr4j.env" ]] || cp pp-ocr4j.env "$APP_DIR/"
chown -R ppocr:ppocr "$APP_DIR"

# ---- systemd ----
cp pp-ocr4j.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable "$SERVICE" >/dev/null
systemctl restart "$SERVICE"

# ---- 等待就绪 ----
PORT=$(grep -E '^\s*port:' "$APP_DIR/config/application-prod.yml" | awk '{print $2}' | head -1)
PORT=${PORT:-8080}
echo "==> 等待服务就绪（http://127.0.0.1:$PORT/actuator/health/readiness）..."
for i in $(seq 1 60); do
  if curl -sf "http://127.0.0.1:$PORT/actuator/health/readiness" >/dev/null 2>&1; then
    echo "==> 部署成功，服务已就绪。常用命令："
    echo "    systemctl status $SERVICE"
    echo "    journalctl -u $SERVICE -f"
    exit 0
  fi
  sleep 2
done
echo "错误：120 秒内未就绪，请检查日志：journalctl -u $SERVICE -n 100" >&2
exit 1
