#!/usr/bin/env python3

import json
import re
import sys
import urllib.request
import xml.sax.saxutils
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TABLE = ROOT / "docs" / "port" / "symbols.json"
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable"
KOTLIN = ROOT / "app" / "src" / "main" / "java" / "ai" / "opencode" / "mobilecode" / "system" / "SymbolNames.kt"
SVG = "https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsrounded/{name}/{variant}/24px.svg"
IOS = ROOT / ".upstream" / "opencode-ios"


def resource_name(sf: str) -> str:
    return "sf_" + sf.replace(".", "_")


def download(material: str, filled: bool) -> str:
    variant = "fill1" if filled else "default"
    url = SVG.format(name=material, variant=variant)
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            return response.read().decode("utf-8")
    except Exception as error:
        raise SystemExit(f"failed to download {material} ({variant}): {error}") from error


def vector_xml(svg: str, mirror: bool) -> str:
    view_box = re.search(r'viewBox="([^"]+)"', svg)
    if not view_box:
        raise SystemExit("svg has no viewBox")
    parts = [float(part) for part in view_box.group(1).split()]
    min_x, min_y, width, height = parts
    paths = re.findall(r"<path\b[^>]*\bd=\"([^\"]+)\"", svg)
    if not paths:
        raise SystemExit("svg has no path")
    mirror_attr = ' android:autoMirrored="true"' if mirror else ""
    body = []
    if min_y < 0:
        body.append(f'    <group android:translateY="{abs(min_y):g}">')
        indent = "        "
    else:
        indent = "    "
    for path in paths:
        escaped = xml.sax.saxutils.escape(path)
        body.append(f'{indent}<path android:fillColor="#FF000000" android:pathData="{escaped}"/>')
    if min_y < 0:
        body.append("    </group>")
    return (
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n'
        '    android:height="24dp"\n'
        f'    android:viewportWidth="{width:g}"\n'
        f'    android:viewportHeight="{height:g}"{mirror_attr}>\n'
        + "\n".join(body)
        + "\n</vector>\n"
    )


def write_kotlin(rows: list[dict]) -> None:
    names = ",\n        ".join(f'"{row["sf"]}"' for row in rows)
    entries = ",\n        ".join(
        f'"{row["sf"]}" to R.drawable.{resource_name(row["sf"])}' for row in rows
    )
    KOTLIN.write_text(
        "package ai.opencode.mobilecode.system\n"
        "\n"
        "import ai.opencode.mobilecode.R\n"
        "\n"
        "internal object SymbolNames {\n"
        "    val all: List<String> = listOf(\n"
        f"        {names},\n"
        "    )\n"
        "\n"
        "    fun drawable(name: String): Int? = drawables[name]\n"
        "\n"
        "    private val drawables: Map<String, Int> = mapOf(\n"
        f"        {entries},\n"
        "    )\n"
        "}\n"
    )


def ios_literals() -> set[str]:
    if not IOS.exists():
        return set()
    pattern = re.compile(r'(?:systemName|systemImage|symbol):\s*"([^"]+)"')
    found = set()
    for path in IOS.rglob("*.swift"):
        if "Tests" in path.parts or "UITests" in path.parts:
            continue
        text = path.read_text(errors="replace")
        found.update(pattern.findall(text))
    return found


def main() -> None:
    rows = json.loads(TABLE.read_text())
    seen = set()
    for row in rows:
        if row["sf"] in seen:
            raise SystemExit(f"duplicate sf name {row['sf']}")
        seen.add(row["sf"])
    missing = sorted(ios_literals() - seen)
    if missing:
        raise SystemExit("symbols.json is missing SF names:\n" + "\n".join(missing))
    DRAWABLE.mkdir(parents=True, exist_ok=True)
    KOTLIN.parent.mkdir(parents=True, exist_ok=True)
    stale = [path for path in DRAWABLE.glob("sf_*.xml") if path.stem not in {resource_name(row["sf"]) for row in rows}]
    for path in stale:
        path.unlink()
    for row in rows:
        svg = download(row["material"], row["filled"])
        xml = vector_xml(svg, row.get("mirror", False))
        (DRAWABLE / f"{resource_name(row['sf'])}.xml").write_text(xml)
    write_kotlin(rows)
    weak = [row["sf"] for row in rows if row.get("match") != "good"]
    print(f"generated {len(rows)} symbols")
    if weak:
        print("not a good match:")
        for name in weak:
            print(f"  {name}")


if __name__ == "__main__":
    sys.exit(main())
