#!/usr/bin/env python3
import re
import sys
from pathlib import Path


def replace_or_insert_setup_flag(content: str) -> str:
    if re.search(r"(?m)^\s*setup:\s*$", content):
        updated, count = re.subn(
            r"(?ms)^(\s*setup:\s*\n)(\s*completed:\s*(?:true|false|null).*\n?)?",
            r"\1  completed: false\n",
            content,
            count=1,
        )
        if count > 0:
            return updated
    if re.search(r"(?m)^\s*security:\s*$", content):
        return re.sub(r"(?m)^(\s*security:\s*)$", r"\1\n  setup:\n    completed: false", content, count=1)
    return content


def replace_provider(content: str) -> str:
    if re.search(r'(?m)^\s*provider:\s*"?[^"\n]+"?\s*$', content):
        return re.sub(r'(?m)^(\s*provider:\s*).+$', r'\1disabled', content, count=1)
    if re.search(r"(?m)^\s*auth:\s*$", content):
        return re.sub(r"(?m)^(\s*auth:\s*)$", r"\1\n    provider: disabled", content, count=1)
    return content


def disable_legacy_basic_auth(content: str) -> str:
    return re.sub(
        r"(?ms)(^\s*auth:\s*\n\s*enabled:\s*)true(\s*$)",
        r"\1false\2",
        content,
        count=1,
    )


def main() -> int:
    target = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("eventlens.yaml")
    if not target.exists():
        print(f"Error: could not find '{target}'.")
        return 1

    content = target.read_text(encoding="utf-8")
    updated = replace_or_insert_setup_flag(content)
    updated = replace_provider(updated)
    updated = disable_legacy_basic_auth(updated)
    target.write_text(updated, encoding="utf-8")
    print(f"Reset setup markers in '{target}'. Restart EventLens to see the setup wizard again.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
