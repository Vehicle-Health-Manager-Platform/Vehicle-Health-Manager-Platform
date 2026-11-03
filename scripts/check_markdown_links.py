#!/usr/bin/env python
"""Check that every relative link and image in the given Markdown files resolves.

Usage:
    python scripts/check_markdown_links.py README.md README.zh-CN.md ...

Only local targets are checked. Absolute URLs and pure anchors are skipped.
Exits non-zero when any target is missing.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
LINK = re.compile(r"!?\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")


def check(path: Path) -> list[str]:
    problems: list[str] = []
    text = path.read_text(encoding="utf-8")
    for target in LINK.findall(text):
        if target.startswith(("http://", "https://", "mailto:", "#")):
            continue
        clean = unquote(target.split("#", 1)[0])
        if not clean:
            continue
        resolved = (path.parent / clean).resolve()
        if not resolved.exists():
            problems.append(f"{path.relative_to(ROOT)}: missing -> {target}")
    return problems


def main(argv: list[str]) -> int:
    files = [Path(a) if Path(a).is_absolute() else ROOT / a for a in argv] or [ROOT / "README.md"]
    problems: list[str] = []
    for f in files:
        if not f.exists():
            problems.append(f"{f}: file not found")
            continue
        problems.extend(check(f))
    if problems:
        print("\n".join(problems))
        print(f"\n{len(problems)} broken link(s)")
        return 1
    print(f"OK: {len(files)} file(s), no broken relative links")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
