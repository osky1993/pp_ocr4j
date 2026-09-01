#!/usr/bin/env python3
"""字段准确率检查器：跑固定测试集，与基线比对，产出门禁判定。

由 scripts/quality-gate.sh 调用，一般不单独执行。

用法：
  quality_check.py --base-url http://localhost:8080 [--update-baseline] [--tests-run N]

三个指标（口径写死在这里，避免各处理解不一致）：
  fieldAccuracy      解析值 == 期望值的字段数 / 期望字段总数
  criticalAccuracy   同上，但只统计 fixture 里 critical 列表内的字段
  falsePositiveRate  期望为 null 却被解析出非空值的字段数 / 期望为 null 的字段总数

falsePositiveRate 单列的原因：漏字段（返回 null）调用方看得见，填错值调用方看不见。
把隔壁标签当值填进来是 OCR 结构化最危险的失败模式，必须独立设限。
"""
import argparse
import json
import statistics
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIXTURE_DIR = ROOT / "test_images" / "fixtures"
BASELINE = ROOT / "quality-baseline.json"

# 门禁阈值
NEW_DOCTYPE_MIN_ACCURACY = 0.90
MAX_FALSE_POSITIVE_RATE = 0.05
PERF_TOLERANCE = 1.2
REPEAT = 3


class Colors:
    OK = "\033[32m"
    WARN = "\033[33m"
    FAIL = "\033[31m"
    OFF = "\033[0m"


def post_image(base_url: str, doc_type: str, image: Path, tier: str) -> dict:
    """以 multipart 上传图片调用结构化解析接口。"""
    boundary = "----ppocr4jQualityGate"
    parts = []
    for name, value in (("tier", tier),):
        parts.append(
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode()
        )
    parts.append(
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
        f"filename=\"{image.name}\"\r\nContent-Type: application/octet-stream\r\n\r\n".encode()
    )
    parts.append(image.read_bytes())
    parts.append(f"\r\n--{boundary}--\r\n".encode())
    body = b"".join(parts)

    req = urllib.request.Request(
        f"{base_url}/api/ocr/parse/{doc_type}",
        data=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    with urllib.request.urlopen(req, timeout=300) as resp:
        payload = json.load(resp)
    if payload.get("code") != 0:
        raise RuntimeError(f"接口返回错误 {payload.get('code')}: {payload.get('message')}")
    return payload["data"]


def score(fixture: dict, actual: dict) -> dict:
    """逐字段比对，产出三项指标与差异明细。"""
    expected = fixture["expected"]
    critical = set(fixture.get("critical", []))

    hits = misses = 0
    crit_hits = crit_total = 0
    null_expected = null_violated = 0
    diffs = []

    for field, want in expected.items():
        got = actual.get(field)
        matched = got == want
        if want is None:
            null_expected += 1
            if not matched:
                null_violated += 1
        if matched:
            hits += 1
        else:
            misses += 1
            kind = "UNEXPECTED" if want is None else ("MISSING" if got is None else "MISMATCH")
            diffs.append({"field": field, "kind": kind, "want": want, "got": got})
        if field in critical:
            crit_total += 1
            if matched:
                crit_hits += 1

    total = hits + misses
    return {
        "fieldAccuracy": round(hits / total, 4) if total else 1.0,
        "criticalAccuracy": round(crit_hits / crit_total, 4) if crit_total else 1.0,
        "falsePositiveRate": round(null_violated / null_expected, 4) if null_expected else 0.0,
        "fields": f"{hits}/{total}",
        "diffs": diffs,
    }


def run_case(base_url: str, fixture: dict) -> dict:
    """跑一个 fixture：重复 REPEAT 次取耗时中位数，字段以首次结果为准（识别是确定性的）。"""
    image = FIXTURE_DIR.parent / fixture["image"]
    if not image.is_file():
        raise FileNotFoundError(f"fixture 图片不存在: {image}")

    costs, data = [], None
    for _ in range(REPEAT):
        data = post_image(base_url, fixture["docType"], image, fixture.get("tier", "tiny"))
        costs.append(data["costMs"])

    result = score(fixture, data["fields"])
    result["costMs"] = int(statistics.median(costs))
    result["docType"] = fixture["docType"]
    result["case"] = fixture["case"]
    return result


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--update-baseline", action="store_true", help="门禁通过后把实测值写回基线")
    ap.add_argument("--tests-run", type=int, default=None, help="本次 mvn test 的用例总数（G2 用）")
    args = ap.parse_args()

    fixtures = sorted(FIXTURE_DIR.glob("*.json"))
    if not fixtures:
        print(f"{Colors.FAIL}没有找到任何 fixture（{FIXTURE_DIR}）{Colors.OFF}")
        return 1

    baseline = json.loads(BASELINE.read_text(encoding="utf-8")) if BASELINE.is_file() else {}
    base_cases = baseline.get("cases", {})

    results, failures, warnings = [], [], []

    print("==> G3 字段准确率")
    for path in fixtures:
        fixture = json.loads(path.read_text(encoding="utf-8"))
        try:
            r = run_case(args.base_url, fixture)
        except Exception as exc:  # noqa: BLE001 - 门禁脚本要把任何失败都算作不通过
            failures.append(f"[{fixture.get('case', path.stem)}] 执行失败: {exc}")
            print(f"  {Colors.FAIL}✗{Colors.OFF} {fixture.get('case', path.stem)}: {exc}")
            continue
        results.append(r)

        prev = base_cases.get(r["case"])
        tag, note = f"{Colors.OK}✓{Colors.OFF}", ""

        # G3a 准确率不回退
        if prev is None:
            if r["fieldAccuracy"] < NEW_DOCTYPE_MIN_ACCURACY:
                failures.append(
                    f"[{r['case']}] 新增用例准确率 {r['fieldAccuracy']:.1%} < {NEW_DOCTYPE_MIN_ACCURACY:.0%}"
                )
                tag = f"{Colors.FAIL}✗{Colors.OFF}"
            note = "（新增基线）"
        elif r["fieldAccuracy"] < prev["fieldAccuracy"]:
            failures.append(
                f"[{r['case']}] 准确率回退 {prev['fieldAccuracy']:.1%} → {r['fieldAccuracy']:.1%}"
            )
            tag = f"{Colors.FAIL}✗{Colors.OFF}"

        # G3b 关键字段零错
        if r["criticalAccuracy"] < 1.0:
            failures.append(f"[{r['case']}] 关键字段准确率 {r['criticalAccuracy']:.1%} < 100%")
            tag = f"{Colors.FAIL}✗{Colors.OFF}"

        # G3c 假阳率
        if r["falsePositiveRate"] > MAX_FALSE_POSITIVE_RATE:
            failures.append(
                f"[{r['case']}] 假阳率 {r['falsePositiveRate']:.1%} > {MAX_FALSE_POSITIVE_RATE:.0%}"
                "（本该为空的字段被填了值）"
            )
            tag = f"{Colors.FAIL}✗{Colors.OFF}"

        print(
            f"  {tag} {r['case']:24} 字段 {r['fields']:>7}  "
            f"准确率 {r['fieldAccuracy']:6.1%}  关键 {r['criticalAccuracy']:6.1%}  "
            f"假阳 {r['falsePositiveRate']:5.1%}  {r['costMs']}ms {note}"
        )
        for d in r["diffs"]:
            print(f"      - {d['kind']:10} {d['field']}: 期望 {d['want']!r} 实际 {d['got']!r}")

    # G4 性能不劣化
    print("==> G4 性能")
    for r in results:
        prev = base_cases.get(r["case"])
        if prev and "costMs" in prev:
            limit = prev["costMs"] * PERF_TOLERANCE
            if r["costMs"] > limit:
                warnings.append(
                    f"[{r['case']}] 耗时 {r['costMs']}ms > 基线 {prev['costMs']}ms × {PERF_TOLERANCE}"
                )
                print(f"  {Colors.WARN}!{Colors.OFF} {r['case']}: {r['costMs']}ms (基线 {prev['costMs']}ms)")
            else:
                print(f"  {Colors.OK}✓{Colors.OFF} {r['case']}: {r['costMs']}ms (基线 {prev['costMs']}ms)")
        else:
            print(f"  {Colors.OK}✓{Colors.OFF} {r['case']}: {r['costMs']}ms（新增基线）")

    # G2 测试数不减少
    if args.tests_run is not None:
        print("==> G2 测试数量")
        prev_n = baseline.get("testsRun")
        if prev_n is not None and args.tests_run < prev_n:
            failures.append(f"测试数减少 {prev_n} → {args.tests_run}")
            print(f"  {Colors.FAIL}✗{Colors.OFF} {prev_n} → {args.tests_run}")
        else:
            print(f"  {Colors.OK}✓{Colors.OFF} {args.tests_run} 个（基线 {prev_n}）")

    print()
    for w in warnings:
        print(f"{Colors.WARN}告警{Colors.OFF} {w}")
    for f in failures:
        print(f"{Colors.FAIL}阻断{Colors.OFF} {f}")

    if failures:
        print(f"\n{Colors.FAIL}门禁未通过：{len(failures)} 项阻断{Colors.OFF}")
        return 1

    if args.update_baseline:
        snapshot = {
            "testsRun": args.tests_run if args.tests_run is not None else baseline.get("testsRun"),
            "cases": {
                r["case"]: {
                    "docType": r["docType"],
                    "fieldAccuracy": r["fieldAccuracy"],
                    "criticalAccuracy": r["criticalAccuracy"],
                    "falsePositiveRate": r["falsePositiveRate"],
                    "costMs": r["costMs"],
                }
                for r in results
            },
        }
        # 基线只升不降：准确率取历史最优，避免"跑一次差的就把标准放低了"
        for case, cur in snapshot["cases"].items():
            old = base_cases.get(case)
            if old:
                cur["fieldAccuracy"] = max(cur["fieldAccuracy"], old.get("fieldAccuracy", 0))
                cur["criticalAccuracy"] = max(cur["criticalAccuracy"], old.get("criticalAccuracy", 0))
        BASELINE.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"{Colors.OK}已更新基线{Colors.OFF} {BASELINE.relative_to(ROOT)}")

    print(f"{Colors.OK}门禁通过{Colors.OFF}" + (f"（{len(warnings)} 项告警）" if warnings else ""))

    # 供 commit message 的 Gates: 尾注直接抄
    print("\n--- Gates 尾注素材 ---")
    if args.tests_run is not None:
        print(f"  tests:    {args.tests_run}/{args.tests_run} pass")
    acc = " | ".join(f"{r['case']} {r['fieldAccuracy']:.0%} ({r['fields']})" for r in results)
    print(f"  accuracy: {acc}")
    print(
        f"            critical {min((r['criticalAccuracy'] for r in results), default=1):.0%} | "
        f"FP-rate {max((r['falsePositiveRate'] for r in results), default=0):.1%}"
    )
    print(f"  perf:     " + " | ".join(f"{r['case']} {r['costMs']}ms" for r in results))
    return 0


if __name__ == "__main__":
    sys.exit(main())
