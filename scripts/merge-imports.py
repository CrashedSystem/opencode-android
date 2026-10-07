#!/usr/bin/env python3
"""Resolve merge conflicts that only touch Kotlin import lines.

Each conflict block becomes the sorted union of both sides' imports. A block
holding anything but imports and blank lines is left alone, and the script
exits 1 so the conflict gets a human.

    python3 scripts/merge-imports.py <file>...
"""
import re
import sys

BLOCK = re.compile(
    r"^<<<<<<< [^\n]*\n(?P<ours>.*?)(?:^\|\|\|\|\|\|\| [^\n]*\n.*?)?^=======\n(?P<theirs>.*?)^>>>>>>> [^\n]*\n",
    re.S | re.M,
)


def resolve(match, unresolved):
    lines = (match["ours"] + match["theirs"]).splitlines()
    if not all(line.startswith("import ") or not line.strip() for line in lines):
        unresolved.append(match.group(0).splitlines()[0])
        return match.group(0)
    imports = sorted({line for line in lines if line.strip()})
    return "".join(line + "\n" for line in imports)


def main(paths):
    unresolved = []
    for path in paths:
        text = open(path).read()
        merged = BLOCK.sub(lambda m: resolve(m, unresolved), text)
        open(path, "w").write(merged)
        print(f"{path}: {len(BLOCK.findall(text))} block(s)")
    for block in unresolved:
        print(f"left for a human: {block}", file=sys.stderr)
    return 1 if unresolved else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
