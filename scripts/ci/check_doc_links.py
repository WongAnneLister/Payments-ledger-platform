#!/usr/bin/env python3
"""校验仓库内 Markdown 的相对链接可解析。

覆盖 docs/ 下全部 Markdown，以及仓库根目录的 Markdown（README 等）。
外部链接（http/https/mailto）与纯锚点不检查。
源码行号锚点（#L<n> / #L<n>-L<m>）必须落在文件范围内且指向非空行：
重新格式化会整体移动行号，越界或落到空行的锚点视为断链。
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
LINE_ANCHOR = re.compile(r"^L(\d+)(?:-L(\d+))?$")


def line_anchor_problem(resolved: Path, anchor: str) -> str | None:
    match = LINE_ANCHOR.match(anchor)
    if not match or not resolved.is_file():
        return None
    lines = resolved.read_text(encoding="utf-8").splitlines()
    for number in filter(None, match.groups()):
        line = int(number)
        if not 1 <= line <= len(lines):
            return f"line {line} is outside 1..{len(lines)}"
        if not lines[line - 1].strip():
            return f"line {line} is blank"
    return None


def main() -> int:
    if not DOCS.is_dir():
        print(f"FAIL: missing {DOCS.relative_to(REPO_ROOT)}")
        return 1

    broken: list[tuple[Path, str, str]] = []
    checked = 0
    # docs/ 下全部 Markdown + 仓库根目录的 Markdown（README 等）
    files = sorted(DOCS.rglob("*.md")) + sorted(REPO_ROOT.glob("*.md"))

    for path in files:
        text = path.read_text(encoding="utf-8")
        for match in LINK.finditer(text):
            target = match.group(2).strip()
            if target.startswith(EXTERNAL) or target.startswith("#"):
                continue
            relative, _, anchor = target.partition("#")
            relative = unquote(relative)
            if not relative:
                continue
            checked += 1
            resolved = (path.parent / relative).resolve()
            if not resolved.exists():
                broken.append((path, target, "missing file"))
            elif problem := line_anchor_problem(resolved, anchor):
                broken.append((path, target, problem))

    print(f"markdown files={len(files)} relative links checked={checked} broken={len(broken)}")
    if broken:
        print("\nFAIL:")
        for path, target, reason in broken:
            print(f"  - {path.relative_to(REPO_ROOT)} -> {target} ({reason})")
        return 1

    print("\nPASS: all relative documentation links resolve")
    return 0


if __name__ == "__main__":
    sys.exit(main())
