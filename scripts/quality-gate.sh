#!/usr/bin/env bash
# 迭代质量门禁：每个迭代结束、git commit 之前执行，全部通过才允许提交。
#
# 用法：
#   scripts/quality-gate.sh                    # 跑全部门禁（会自动起服务并在结束时关闭）
#   scripts/quality-gate.sh --skip-tests       # 跳过 mvn test（刚跑过时省 30s）
#   scripts/quality-gate.sh --update-baseline  # 门禁通过后把实测值写回 quality-baseline.json
#   scripts/quality-gate.sh --base-url URL     # 复用已在跑的服务，不自己启动
#
# 门禁项（详见 test_images/fixtures/ 与 quality-baseline.json）：
#   G1  测试全绿          mvn test 零 Failures 零 Errors
#   G2  测试数不减少      防止删测试换绿灯
#   G3a 字段准确率不回退  对固定测试集逐字段比对，已有 docType 只能升不能降
#   G3b 关键字段零错      critical 字段 100%，一个都不许错
#   G3c 假阳率 ≤ 5%       本该为空的字段被填了值（最危险的失败模式）
#   G4  性能不劣化        tiny 档耗时 ≤ 基线 × 1.2（告警级）
#   G5  无真实证件入库    test_images/ 新增文件必须在 README.md 登记来源与许可
#   G6  Bean 契约         ResultBeanContractTest（无 lombok，漏 getter 会静默丢字段）
#   G7  文档同步          DocsSyncTest（README docType 表格 ⊇ supportedTypes()）
#
# 退出码：0 = 全部通过；1 = 存在阻断项
set -euo pipefail
cd "$(dirname "$0")/.."

SKIP_TESTS=false
UPDATE_BASELINE=false
BASE_URL=""
OWN_SERVER=false
SERVER_PID=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-tests)      SKIP_TESTS=true; shift ;;
    --update-baseline) UPDATE_BASELINE=true; shift ;;
    --base-url)        BASE_URL="$2"; shift 2 ;;
    -h|--help)         sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "未知参数：$1（-h 查看用法）" >&2; exit 2 ;;
  esac
done

cleanup() {
  if $OWN_SERVER && [[ -n "$SERVER_PID" ]]; then
    echo "==> 关闭本脚本启动的服务（pid $SERVER_PID）"
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

TESTS_RUN=""

# ---------------------------------------------------------------- G1 / G2 / G6 / G7
if $SKIP_TESTS; then
  echo "==> 跳过 mvn test（--skip-tests），G1/G2/G6/G7 本次不校验"
else
  echo "==> G1/G2/G6/G7 运行测试套件"
  TEST_LOG=$(mktemp)
  if ! mvn -B -o test > "$TEST_LOG" 2>&1; then
    echo "✗ G1 测试未通过：" >&2
    grep -E "Tests run|ERROR|FAIL" "$TEST_LOG" | head -30 >&2
    exit 1
  fi
  # 取汇总行（surefire 最后一行 Tests run 是总计）
  SUMMARY=$(grep -E "^\[INFO\] Tests run:" "$TEST_LOG" | tail -1)
  TESTS_RUN=$(sed -E 's/.*Tests run: ([0-9]+).*/\1/' <<< "$SUMMARY")
  SKIPPED=$(sed -E 's/.*Skipped: ([0-9]+).*/\1/' <<< "$SUMMARY")
  echo "  ✓ $SUMMARY"
  if [[ "${SKIPPED:-0}" != "0" ]]; then
    echo "✗ G1 存在被跳过的测试（Skipped=$SKIPPED），门禁要求零 skip" >&2
    exit 1
  fi
  rm -f "$TEST_LOG"
fi

# ---------------------------------------------------------------- G5 测试图登记
echo "==> G5 测试图来源登记检查"
UNREGISTERED=0
while IFS= read -r img; do
  name=$(basename "$img")
  if ! grep -qF "$name" test_images/README.md; then
    echo "  ✗ $name 未在 test_images/README.md 登记来源与许可" >&2
    UNREGISTERED=$((UNREGISTERED + 1))
  fi
done < <(find test_images -maxdepth 1 -type f ! -name "README.md" ! -name ".*")
if [[ $UNREGISTERED -gt 0 ]]; then
  echo "✗ G5 未通过：$UNREGISTERED 个测试图未登记（禁止提交真实证件图片）" >&2
  exit 1
fi
echo "  ✓ 全部测试图已登记来源与许可"

# ---------------------------------------------------------------- 起服务
if [[ -z "$BASE_URL" ]]; then
  BASE_URL="http://localhost:8080"
  if curl -sf -m 2 "$BASE_URL/actuator/health/readiness" >/dev/null 2>&1; then
    echo "==> 复用已在运行的服务（$BASE_URL）"
  else
    echo "==> 启动服务用于准确率检查"
    mvn -q -o spring-boot:run > /tmp/pp-ocr4j-gate.log 2>&1 &
    SERVER_PID=$!
    OWN_SERVER=true
    for _ in $(seq 1 60); do
      if curl -sf -m 2 "$BASE_URL/actuator/health/readiness" >/dev/null 2>&1; then break; fi
      sleep 2
    done
    if ! curl -sf -m 2 "$BASE_URL/actuator/health/readiness" >/dev/null 2>&1; then
      echo "✗ 服务 120s 内未就绪，见 /tmp/pp-ocr4j-gate.log" >&2
      tail -20 /tmp/pp-ocr4j-gate.log >&2
      exit 1
    fi
    echo "  ✓ 服务就绪"
  fi
fi

# ---------------------------------------------------------------- G3 / G4
ARGS=(--base-url "$BASE_URL")
[[ -n "$TESTS_RUN" ]] && ARGS+=(--tests-run "$TESTS_RUN")
$UPDATE_BASELINE && ARGS+=(--update-baseline)

python3 scripts/quality_check.py "${ARGS[@]}"
