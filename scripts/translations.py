#!/usr/bin/env python3
"""Lists the app's string files, or checks one language's translations (docs/translating.md).

Usage:
  scripts/translations.py            lists every English string file and its size
  scripts/translations.py de         reports what the `values-de` folders are missing

For a language it reports strings with no translation, translations whose English string is
gone, and translations whose placeholders (%1$s, %2$d) differ from the English text.
Exits 1 when a placeholder differs or a translation is stale, since those break the build or
the app; missing strings only show in English.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def english_files():
    files = []
    for path in sorted(ROOT.glob("**/src/main/res/values/strings*.xml")):
        if "build" in path.relative_to(ROOT).parts:
            continue
        files.append(path)
    return files


def placeholders(text):
    return sorted(re.findall(r"%\d+\$[sdf]", text))


def read_strings(path):
    """Returns name -> list of texts (one for a string, one per form for a plural)."""
    entries = {}
    for element in ET.parse(path).getroot():
        name = element.get("name")
        if element.get("translatable") == "false":
            continue
        if element.tag == "string":
            entries[name] = ["".join(element.itertext())]
        if element.tag == "plurals":
            entries[name] = ["".join(item.itertext()) for item in element]
    return entries


def list_files():
    total = 0
    for path in english_files():
        count = len(read_strings(path))
        total += count
        print(f"{count:5}  {path.relative_to(ROOT)}")
    print(f"{total:5}  strings to translate")


def check_language(code):
    missing = 0
    broken = 0
    for english_path in english_files():
        english = read_strings(english_path)
        translated_path = english_path.parent.parent / f"values-{code}" / english_path.name
        relative = translated_path.relative_to(ROOT)
        if not translated_path.exists():
            print(f"{relative}: file missing ({len(english)} strings)")
            missing += len(english)
            continue
        translated = read_strings(translated_path)
        for name in english:
            if name not in translated:
                print(f"{relative}: missing {name}")
                missing += 1
                continue
            wanted = set()
            for text in english[name]:
                wanted.update(placeholders(text))
            for text in translated[name]:
                if not set(placeholders(text)).issubset(wanted):
                    print(f"{relative}: {name} has placeholders the English text doesn't: {text}")
                    broken += 1
            if len(english[name]) == 1 and placeholders(translated[name][0]) != placeholders(english[name][0]):
                print(f"{relative}: {name} placeholders differ from English")
                broken += 1
        for name in translated:
            if name not in english:
                print(f"{relative}: {name} no longer exists in English")
                broken += 1
    print(f"{missing} missing, {broken} to fix")
    return 1 if broken else 0


if len(sys.argv) == 1:
    list_files()
    sys.exit(0)
sys.exit(check_language(sys.argv[1]))
