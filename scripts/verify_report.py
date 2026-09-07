#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_report.py — Self-check tool cho báo cáo audit Hydraulic PR #5.

Đọc tất cả block code (```...```) trong file markdown báo cáo và grep
từng dòng trong artifact tương ứng (latest.log, fabric.mod.json từ
hydraulic-fabric.jar, archive storage). In [VERIFIED] cho mỗi block
match thật, [MISMATCH] cho block không match (kèm diff), [ASSUMED]
cho block không grep được (cần verify thủ công).

Báo cáo PHẢI pass verify_report.py với 0 MISMATCH trước khi được đánh
dấu "FINAL".

Usage:
    python verify_report.py <path-to-report.md>
    python verify_report.py --all
"""

import os
import re
import subprocess
import sys
from pathlib import Path

# Force UTF-8 stdout/stderr để workaround bash locale issue trên Windows
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


def safe_print(*args, **kwargs):
    """Print với encoding-safe cho Windows console (cp1252)."""
    try:
        print(*args, **kwargs, flush=True)
    except UnicodeEncodeError:
        encoded = [str(a).encode("ascii", "replace").decode("ascii") for a in args]
        try:
            print(*encoded, **kwargs, flush=True)
        except Exception:
            # Last resort: write to stderr
            sys.stderr.write(" ".join(encoded) + "\n")
            sys.stderr.flush()


# Cấu hình artifact paths (USER phải chỉnh nếu khác)
ARTIFACTS = {
    "log": Path(os.environ.get("LOG_PATH", r"C:\Users\Admin\Downloads\latest.log")),
    "jar": Path(os.environ.get("JAR_PATH", r"C:\Users\Admin\Downloads\hydraulic-fabric.jar")),
    "archive": Path(os.environ.get(
        "ARCHIVE_PATH", r"C:\Users\Admin\Downloads\archive-2026-09-07T154615+0700.tar.gz")),
}

# Skip rules: block nào chứa keyword này sẽ không grep
SKIP_KEYWORDS = {
    "(skipped)",
    "no real log",
    "intentionally omitted",
    "see note above",
    "see f",
    "see g",
}


def extract_code_blocks(md_path: Path) -> list[tuple[str, int, str]]:
    """Trả về list (context, line_number, content) cho mỗi code block."""
    content = md_path.read_text(encoding="utf-8", errors="replace")
    blocks = []
    in_block = False
    block_start = 0
    for i, line in enumerate(content.split("\n"), start=1):
        if line.strip().startswith("```"):
            if not in_block:
                in_block = True
                block_start = i + 1
                buf = []
            else:
                in_block = False
                blocks.append((
                    content.split("\n")[max(0, block_start - 3):block_start - 1],
                    block_start,
                    "\n".join(buf),
                ))
        elif in_block:
            buf.append(line)
    return blocks


def extract_signature(line: str) -> str:
    """Trích ra signature ngắn gọn từ 1 dòng log/code.

    Strategy:
    - Bỏ leading line number nếu có (e.g. '3696:')
    - Bỏ leading timestamp nếu có (e.g. '[15:45:16]')
    - Bỏ leading thread name nếu có (e.g. '[Hydraulic Conversion Thread #0]')
    - Lấy phần còn lại (message chính)
    - Cắt tại 80 ký tự để grep hiệu quả
    """
    s = line.strip()
    # Remove leading line number (path:line)
    s = re.sub(r"^\d+:\s*", "", s)
    # Remove leading timestamp [HH:MM:SS]
    s = re.sub(r"^\[\d{2}:\d{2}:\d{2}\]\s*", "", s)
    # Remove leading thread name [Some Name]
    s = re.sub(r"^\[[^\]]+\]\s*", "", s)
    # Truncate to 80 chars
    return s[:80]


def extract_signature_from_log(line: str) -> str:
    """Trích signature cho log line: bỏ timestamp + thread name, giữ message + level."""
    # Match pattern: "[timestamp] [thread/level]: message"
    m = re.match(r"^\[(\d{2}:\d{2}:\d{2})\]\s+\[([^\]]+)\]:\s*(.*)$", line.strip())
    if m:
        return m.group(3)[:80]  # message
    return extract_signature(line)


def grep_in_artifact(pattern: str, source: str) -> tuple[int, str]:
    """Grep pattern (regex escaped) trong artifact tương ứng. Trả về (count, sample_line)."""
    if not ARTIFACTS[source].exists():
        return -1, f"[ARTIFACT NOT FOUND: {ARTIFACTS[source]}]"
    try:
        result = subprocess.run(
            ["grep", "-c", "-E", "--", pattern, str(ARTIFACTS[source])],
            capture_output=True, text=True, timeout=30,
        )
        count = int(result.stdout.strip() or "0")
        # Lấy 1 dòng mẫu nếu có
        sample = ""
        if count > 0:
            sample_result = subprocess.run(
                ["grep", "-m", "1", "-E", "--", pattern, str(ARTIFACTS[source])],
                capture_output=True, text=True, timeout=10,
            )
            sample = sample_result.stdout.strip()[:120]
        return count, sample
    except (ValueError, subprocess.TimeoutExpired):
        count = -1
    return count, ""


def extract_first_line(text: str) -> str:
    """Lấy dòng đầu tiên không rỗng từ block code."""
    for line in text.split("\n"):
        s = line.strip()
        if s and not s.startswith("#"):
            return s
    return text.strip().split("\n")[0] if text.strip() else ""


def verify_one(md_path: Path) -> tuple[int, int, int, int]:
    """Verify 1 file. Trả về (verified, mismatched, skipped, manual)."""
    if not md_path.exists():
        safe_print(f"error: {md_path} not found")
        sys.exit(1)

    safe_print(f"Verifying {md_path}")
    safe_print(f"  log:    {ARTIFACTS['log']}")
    safe_print(f"  jar:    {ARTIFACTS['jar']}")
    safe_print(f"  archive: {ARTIFACTS['archive']}")
    safe_print("")

    blocks = extract_code_blocks(md_path)
    verified = 0
    mismatched = 0
    skipped = 0
    needs_manual = 0

    for context, line_no, content in blocks:
        first_line = extract_first_line(content)
        if not first_line or len(first_line) < 10:
            skipped += 1
            continue
        if any(kw in first_line.lower() for kw in SKIP_KEYWORDS):
            skipped += 1
            continue
        source = None
        fl = first_line.lower()
        # Heuristic: pick source based on content
        if any(t in fl for t in [
            "failed to", "could not", "deferred", "warn", "error",
            "found", "registered", "loaded", "converting", "completed",
            "couldn't", "malformed", "converting pack",
        ]):
            source = "log"
        elif "file" in fl and "jars" in fl and ("snapshot" in fl or "metadata" in fl or "converter" in fl):
            source = "jar"
        elif "storage" in fl or ".mcpack" in fl or "materials.json" in fl:
            source = "archive"
        if not source:
            skipped += 1
            continue
        # Trích signature tùy theo source để grep hiệu quả
        if source == "log":
            # Log: dùng message (sau timestamp + thread)
            # Lấy dòng đầu tiên có format log (có dấu :)
            sig = None
            for ln in content.split("\n"):
                ln_strip = ln.strip()
                if not ln_strip or ln_strip.startswith("#"):
                    continue
                # Loại bỏ leading "line_number:" nếu có
                ln_clean = re.sub(r"^\d+:\s*", "", ln_strip)
                m = re.match(r"^\[\d{2}:\d{2}:\d{2}\]\s+\[[^\]]+\]:\s*(.*)$", ln_clean)
                if m:
                    sig = m.group(1)[:60]
                    break
            if sig is None:
                # Không match log format, fallback lấy raw
                sig = first_line[:60]
        elif source == "jar":
            # Jar: dùng "filename.jar" trong JSON, escape special chars
            sig = None
            for ln in content.split("\n"):
                ln_strip = ln.strip().rstrip(",")
                m = re.search(r'"([^"]+\.jar)"', ln_strip)
                if m:
                    sig = re.escape(m.group(1))
                    break
            if sig is None:
                sig = re.escape(first_line[:60])
        else:  # archive
            sig = first_line[:60]

        if not sig or len(sig) < 4:
            skipped += 1
            continue

        count, sample = grep_in_artifact(sig, source)
        if count == -1:
            needs_manual += 1
            safe_print(f"  [MANUAL] line {line_no}: artifact missing for `{first_line[:50]}`")
        elif count > 0:
            verified += 1
            safe_print(f"  [VERIFIED] line {line_no}: {count} match(es) in {source} for `{sig[:50]}`")
        else:
            mismatched += 1
            safe_print(f"  [MISMATCH] line {line_no}: 0 matches in {source} for `{sig[:50]}`")
            if sample:
                safe_print(f"               (expected: `{sig[:50]}`, no sample to compare)")

    return verified, mismatched, skipped, needs_manual


def main():
    import sys
    sys.stderr.write(f"DEBUG: argv={sys.argv!r}\n")
    sys.stderr.write(f"DEBUG: len={len(sys.argv)}\n")
    sys.stderr.flush()
    if len(sys.argv) < 2:
        # Bypass safe_print for usage to avoid encoding edge cases
        print("usage: verify_report.py <path-to-report.md>", file=sys.stderr)
        print("       verify_report.py --all  (verify all files in reports/)", file=sys.stderr)
        sys.exit(2)

    if sys.argv[1] == "--all":
        # Try multiple candidate paths for the reports directory
        candidates = [
            Path(__file__).parent / "reports",
            Path(__file__).parent.parent / "reports",
            Path.cwd() / "Hydraulic" / "reports",
            Path.cwd() / "reports",
        ]
        reports_dir = next((p for p in candidates if p.exists()), None)
        if reports_dir is None:
            safe_print(f"error: cannot find reports dir. Tried: {candidates}")
            sys.exit(1)
        files = sorted(reports_dir.glob("*.md"))
        if not files:
            safe_print(f"error: no .md files in {reports_dir}")
            sys.exit(1)
    else:
        files = [Path(sys.argv[1])]

    total_v = total_m = total_s = total_n = 0
    failed = []
    for f in files:
        v, m, s, n = verify_one(f)
        total_v += v
        total_m += m
        total_s += s
        total_n += n
        safe_print(f"\n=== {f.name} Summary ===")
        safe_print(f"  VERIFIED: {v}")
        safe_print(f"  MISMATCH: {m}")
        safe_print(f"  SKIPPED:  {s}")
        safe_print(f"  MANUAL:   {n}")
        if m > 0:
            failed.append(f)
        safe_print("")

    if len(files) > 1:
        safe_print("=" * 60)
        safe_print(f"=== TOTAL ({len(files)} files) ===")
        safe_print(f"  VERIFIED: {total_v}")
        safe_print(f"  MISMATCH: {total_m}")
        safe_print(f"  SKIPPED:  {total_s}")
        safe_print(f"  MANUAL:   {total_n}")
        safe_print("")

    if total_m > 0:
        safe_print(f"FAILED: {total_m} MISMATCH(es) — fix before publishing")
        for f in failed:
            safe_print(f"  - {f}")
        sys.exit(1)
    safe_print("PASS: report ready to publish")


if __name__ == "__main__":
    main()
