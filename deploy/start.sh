#!/usr/bin/env bash
# 前台启动 pp-ocr4j（systemd ExecStart 调用；也可在安装目录手动运行调试）
set -euo pipefail
cd "$(dirname "$0")/.."

# shellcheck disable=SC1091
[[ -f pp-ocr4j.env ]] && source pp-ocr4j.env

exec "${JAVA_BIN:-java}" ${JAVA_OPTS:-} -jar pp-ocr4j.jar \
  --spring.profiles.active="${SPRING_PROFILES_ACTIVE:-prod}"
