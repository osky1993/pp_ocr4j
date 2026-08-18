#!/usr/bin/env bash
# 一键部署：上传 dist/pp-ocr4j-cpu.tar.gz 到服务器并执行远端安装
#
# 用法：
#   scripts/deploy.sh user@server            # 使用已有 dist 包
#   scripts/deploy.sh user@server --package  # 先重新打包再部署
#
# 要求：ssh 免密或可交互输密码；远端用户可 sudo。
set -euo pipefail
cd "$(dirname "$0")/.."

TARGET=${1:-}
[[ -n "$TARGET" ]] || { echo "用法：scripts/deploy.sh user@server [--package]" >&2; exit 1; }

if [[ "${2:-}" == "--package" || ! -f dist/pp-ocr4j-cpu.tar.gz ]]; then
  scripts/package.sh
fi

REMOTE_TMP=/tmp/pp-ocr4j-deploy
echo "==> 上传部署包到 $TARGET:$REMOTE_TMP"
ssh "$TARGET" "rm -rf $REMOTE_TMP && mkdir -p $REMOTE_TMP"
scp dist/pp-ocr4j-cpu.tar.gz "$TARGET:$REMOTE_TMP/"

echo "==> 远端解压并安装"
ssh -t "$TARGET" "cd $REMOTE_TMP && tar -xzf pp-ocr4j-cpu.tar.gz && cd pp-ocr4j && sudo bash install.sh && rm -rf $REMOTE_TMP"
