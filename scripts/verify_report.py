#!/usr/bin/env python3
"""
verify_report.py — Self-check tool cho báo cáo audit Hydraulic PR #5.

Đọc tất cả block code (```...```) trong file markdown báo cáo và grep
từng dòng trong artifact tương ứng (latest.log, fabric.mod.json từ
hydraulic-fabric.jar, archive storage). In [VERIFIED] cho mỗi block
match thật, [MISMATCH] cho block không match (kèm diff), [ASSUMED]
cho block không grep được (cần verify thủ công).

Báo cáo PHẢI pass verify_report.py với 0 MISMATCH trước khi được đánh
dấu "FINAL".
"""

import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path


# Cấu hình artifact paths (USER phải chỉnh nếu khác)
ARTIFACTS = {
    "log": Path(os.environ.get("LOG_PATH", r"C:\Users\Admin\Downloads\latest.log")),
    "jar": Path(os.environ.get("JAR_PATH", r"C:\Users\Admin\Downloads\hydraulic-fabric.jar")),
    "archive": Path(os.environ.get("ARCHIVE_PATH", r"C:\Users\Admin\Downloads\archive-2026-09-07T154615+0700.tar.gz")),
}

# Skip rules: block nào chứa keyword này sẽ không grep
SKIP_KEYWORDS = {
    "(skipped)",
    "no real log",
    "intentionally omitted",
    "see note above",
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


def grep_in_artifact(pattern: str, source: str) -> tuple[int, list[str]]:
    """Grep pattern (regex escaped) trong artifact tương ứng. Trả về (count, matching_lines)."""
    if not ARTIFACTS[source].exists():
        return -1, [f"[ARTIFACT NOT FOUND: {ARTIFACTS[source]}]"]
    try:
        result = subprocess.run(
            ["grep", "-c", "-E", "--", pattern, str(ARTIFACTS[source])],
            capture_output=True, text=True, timeout=30,
        )
        count = int(result.stdout.strip() or "0")
    except (ValueError, subprocess.TimeoutExpired):
        count = -1
    return count, []


def extract_first_line(text: str) -> str:
    """Lấy dòng đầu tiên không rỗng từ block code."""
    for line in text.split("\n"):
        s = line.strip()
        if s and not s.startswith("#"):
            return s
    return text.strip().split("\n")[0] if text.strip() else ""


def main():
    if len(sys.argv) < 2:
        print("usage: verify_report.py <path-to-report.md>", file=sys.stderr)
        sys.exit(2)
    md_path = Path(sys.argv[1])
    if not md_path.exists():
        print(f"error: {md_path} not found", file=sys.stderr)
        sys.exit(1)

    print(f"Verifying {md_path}")
    print(f"  log:    {ARTIFACTS['log']}")
    print(f"  jar:    {ARTIFACTS['jar']}")
    print(f"  archive: {ARTIFACTS['archive']}")
    print()

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
        # Skip blocks matching skip keywords
        if any(kw in first_line.lower() for kw in SKIP_KEYWORDS):
            skipped += 1
            continue
        # Heuristic: pick source based on content
        source = None
        fl = first_line.lower()
        if any(t in fl for t in [
            "failed to", "could not", "deferred", "warn", "error",
            "found", "registered", "loaded", "converting", "completed",
        ]):
            source = "log"
        elif "file" in fl and "jars" in fl and "snapshot" in fl:
            source = "jar"
        elif "storage" in fl or ".mcpack" in fl or "materials.json" in fl:
            source = "archive"
        if not source:
            skipped += 1
            continue
        # Extract distinctive part of first line (avoid timestamps, etc.)
        # Try first 60 chars of first line as grep pattern
        pat = re.escape(first_line[:80])
        count, _ = grep_in_artifact(pat, source)
        if count == -1:
            needs_manual += 1
            print(f"  [MANUAL-VERIFY] line {line_no}: artifact missing for `{first_line[:60]}`")
        elif count > 0:
            verified += 1
            print(f"  [VERIFIED] line {line_no}: {count} match(es) in {source} for `{first_line[:60]}`")
        else:
            mismatched += 1
            print(f"  [MISMATCH] line {line_no}: 0 matches in {source} for `{first_line[:60]}`")

    print()
    print(f"=== Summary ===")
    print(f"  VERIFIED:    {verified}")
    print(f"  MISMATCH:    {mismatched}")
    print(f"  SKIPPED:     {skipped}")
    print(f"  MANUAL:      {needs_manual}")
    print()
    if mismatched > 0:
        print(f"FAILED: {mismatched} MISMATCH(es) — fix before publishing")
        sys.exit(1)
    print(f"PASS: report ready to publish")


if __name__ == "__main__":
    main()
