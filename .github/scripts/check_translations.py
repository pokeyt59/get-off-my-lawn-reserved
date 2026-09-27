#!/usr/bin/env python3
"""Fails when GOML code uses a translation key that en_us.json doesn't have (players would see the raw key),
or when a language file isn't valid JSON. Keys built at runtime ("text.goml.command." + name) can't be checked here,
the game tests check what those produce."""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LANG = ROOT / "src" / "main" / "resources" / "data" / "goml" / "lang"
KEY = re.compile(r'"((?:text|block|item|gui)\.goml\.[A-Za-z0-9_./-]+)"(\s*\+)?')


def main():
    problems = []
    languages = {}
    for file in sorted(LANG.glob("*.json")):
        try:
            languages[file.stem] = json.loads(file.read_text(encoding="utf-8"))
        except json.JSONDecodeError as error:
            problems.append(f"{file.relative_to(ROOT)} isn't valid JSON: {error}")

    english = languages.get("en_us", {})
    used = 0
    for source in sorted((ROOT / "src" / "main" / "java").rglob("*.java")):
        text = source.read_text(encoding="utf-8")
        for match in KEY.finditer(text):
            key, concatenated = match.group(1), match.group(2)
            # A prefix that gets the rest appended at runtime
            if concatenated or key.endswith("."):
                continue
            used += 1
            if key not in english:
                line = text.count("\n", 0, match.start()) + 1
                problems.append(f"{source.relative_to(ROOT)}:{line}: {key} isn't in en_us.json")

    for problem in problems:
        print(f"::error::{problem}")
    if problems:
        sys.exit(1)
    print(f"{used} translation keys in the code, all in en_us.json; {len(languages)} language files are valid JSON")


if __name__ == "__main__":
    main()
