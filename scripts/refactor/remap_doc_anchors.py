"""Move 'File.java#L<n>' documentation links to where their line went after a refactor.

For every link into a Java file that changed since <base>, the line the link pointed to at <base> is looked up in the
current file, comparing without whitespace and after applying renames (old=new). A link is moved only when that line
occurs exactly once; everything else is listed for manual review, because a line that no longer exists usually means
the code moved to another file and the link must be retargeted by hand.

Link text that repeats the line number ('Foo.java:123', 'line 123') is updated with it.

usage: remap_doc_anchors.py <base> [old=new ...] -- <doc.md ...>
"""
import os
import re
import subprocess
import sys

LINK = re.compile(r'\[([^\]]*)\]\(([^)#\s]+\.java)#L(\d+)\)')


def normalize(line, renames=()):
    for old, new in renames:
        line = re.sub(r'(?<![\w.])' + re.escape(old) + r'\b', new, line)
    return re.sub(r'\s+', '', line)


def relocate(old_line, new_lines, renames=()):
    """1-based line numbers in new_lines whose content matches old_line; empty for blank lines."""
    target = normalize(old_line, renames)
    if not target:
        return []
    return [i + 1 for i, line in enumerate(new_lines) if normalize(line, renames) == target]


def retarget_label(label, old_number, new_number):
    return re.sub(rf'(?<![\d.])(:|line ){old_number}\b', lambda m: f'{m.group(1)}{new_number}', label)


def remap(text, doc, changed, old_file, new_file, renames=()):
    """Returns (text, moved, manual) for one document; old_file/new_file map a repo path to its lines."""
    moved, manual = 0, []

    def fix(match):
        nonlocal moved
        label, href, number = match.group(1), match.group(2), int(match.group(3))
        path = os.path.relpath(os.path.normpath(os.path.join(os.path.dirname(doc), href))).replace(os.sep, '/')
        if path not in changed:
            return match.group(0)
        old = old_file(path)
        hits = relocate(old[number - 1] if number <= len(old) else '', new_file(path), renames)
        if len(hits) != 1:
            snippet = old[number - 1].strip()[:90] if number <= len(old) else '<past end>'
            manual.append(f'{doc}: {path}#L{number} ({len(hits)} matches) old: {snippet}')
            return match.group(0)
        if hits[0] != number:
            moved += 1
            label = retarget_label(label, number, hits[0])
        return f'[{label}]({href}#L{hits[0]})'

    return LINK.sub(fix, text), moved, manual


def main(argv=None):
    args = list(sys.argv[1:] if argv is None else argv)
    if '--' not in args or not args:
        print(__doc__)
        return 2
    base = args.pop(0)
    split = args.index('--')
    renames = [tuple(rename.split('=', 1)) for rename in args[:split]]
    docs = args[split + 1:]
    diff = subprocess.run(['git', 'diff', '--name-only', base, '--', 'src'], capture_output=True, text=True, check=True)
    changed = set(diff.stdout.split())

    def old_file(path):
        shown = subprocess.run(['git', 'show', f'{base}:{path}'], capture_output=True, text=True, encoding='utf-8')
        return shown.stdout.split('\n')

    def new_file(path):
        with open(path, encoding='utf-8') as source:
            return source.read().split('\n')

    total, review, results = 0, [], {}
    for doc in docs:
        with open(doc, encoding='utf-8') as source:
            text, moved, manual = remap(source.read(), doc, changed, old_file, new_file, renames)
        results[doc] = text
        total += moved
        review += manual
    # Write only after every document was processed, so a failure cannot leave half the links remapped.
    for doc, text in results.items():
        with open(doc, 'w', encoding='utf-8', newline='') as target:
            target.write(text)
    print(f'moved {total} anchors; manual review: {len(review)}')
    for line in review:
        print('  ' + line)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
