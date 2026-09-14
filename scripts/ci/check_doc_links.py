#!/usr/bin/env python3
"""校验仓库内 Markdown 的相对链接可解析。

覆盖 docs/ 下全部 Markdown，以及仓库根目录的 Markdown（README 等）。
外部链接（http/https/mailto）与纯锚点不检查。
退出码非 0 表示存在断链，CI 失败。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote

REPO_ROOT = Path(__file__).resolve().parents[2]
DOCS = REPO_ROOT / "docs"

LINK = re.compile(r"\[([^\]]*)\]\(([^)]+)\)")
EXTERNAL = ("http://", "https://", "mailto:")


def main() -> int:
    if not DOCS.is_dir():
        print(f"FAIL: missing {DOCS.relative_to(REPO_ROOT)}")
        return 1

    broken: list[tuple[Path, str, Path]] = []
    checked = 0
    # docs/ 下全部 Markdown + 仓库根目录的 Markdown（README 等）
    files = sorted(DOCS.rglob("*.md")) + sorted(REPO_ROOT.glob("*.md"))

    for path in files:
        text = path.read_text(encoding="utf-8")
        for match in LINK.finditer(text):
            target = match.group(2).strip()
            if target.startswith(EXTERNAL) or target.startswith("#"):
                continue
            relative = unquote(target.split("#", 1)[0])
            if not relative:
                continue
            checked += 1
            resolved = (path.parent / relative).resolve()
            if not resolved.exists():
                broken.append((path, target, resolved))

    print(f"markdown files={len(files)} relative links checked={checked} broken={len(broken)}")
    if broken:
        print("\nFAIL:")
        for path, target, resolved in broken:
            print(f"  - {path.relative_to(REPO_ROOT)} -> {target}")
        return 1

    print("\nPASS: all relative documentation links resolve")
    return 0


if __name__ == "__main__":
    sys.exit(main())
