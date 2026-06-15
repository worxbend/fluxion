#!/usr/bin/env python3
"""Validate GraalVM reflection metadata for source-derived reflective types."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REFLECT_CONFIG = ROOT / "sysboot" / "graal" / "reflect-config.json"
SPECIAL_REQUIRED = {
    "dev.sysboot.cli.option.GlobalOptions",
    "dev.sysboot.cli.output.OutputFormat",
}
MANAGED_PREFIXES = (
    "dev.sysboot.cli.command.",
    "dev.sysboot.config.yaml.contract.",
    "dev.sysboot.executor.state.record.",
)


def main() -> int:
    reflected = reflect_config_names()
    required = required_class_names()
    missing = sorted(required - reflected)
    stale = sorted(
        name for name in reflected if name.startswith(MANAGED_PREFIXES) and name not in required
    )
    if not missing and not stale:
        print("Native metadata check passed.")
        return 0
    if missing:
        print("Missing GraalVM reflection metadata:", file=sys.stderr)
        for name in missing:
            print(f"  {name}", file=sys.stderr)
    if stale:
        print("Stale GraalVM reflection metadata:", file=sys.stderr)
        for name in stale:
            print(f"  {name}", file=sys.stderr)
    return 1


def reflect_config_names() -> set[str]:
    with REFLECT_CONFIG.open(encoding="utf-8") as config:
        entries = json.load(config)
    return {entry["name"] for entry in entries}


def required_class_names() -> set[str]:
    names = set(SPECIAL_REQUIRED)
    names.update(cli_command_classes())
    names.update(source_classes(ROOT / "sysboot" / "config-parser" / "src", include_nested=True))
    names.update(source_classes(ROOT / "sysboot" / "executor" / "src", include_nested=False))
    return names


def cli_command_classes() -> set[str]:
    names: set[str] = set()
    command_dir = ROOT / "sysboot" / "cli" / "src" / "dev" / "sysboot" / "cli" / "command"
    for source_file in sorted(command_dir.glob("*.java")):
        source = source_file.read_text(encoding="utf-8")
        package_name = java_package(source)
        top_level = top_level_name(source)
        if top_level is None:
            continue
        fqcn = f"{package_name}.{top_level}"
        if "@Command" in source or "implements IVersionProvider" in source:
            names.add(fqcn)
        names.update(nested_command_classes(source, fqcn))
    return names


def source_classes(source_root: Path, include_nested: bool) -> set[str]:
    names: set[str] = set()
    for source_file in sorted(source_root.rglob("*.java")):
        source = source_file.read_text(encoding="utf-8")
        package_name = java_package(source)
        top_level = top_level_name(source)
        if top_level is None:
            continue
        fqcn = f"{package_name}.{top_level}"
        if fqcn.startswith(MANAGED_PREFIXES):
            names.add(fqcn)
            if include_nested:
                names.update(nested_static_classes(source, fqcn))
    return names


def java_package(source: str) -> str:
    match = re.search(r"^package\s+([\w.]+);", source, re.MULTILINE)
    if match is None:
        raise ValueError("Java source has no package declaration")
    return match.group(1)


def top_level_name(source: str) -> str | None:
    match = re.search(
        r"^public\s+(?:abstract\s+|final\s+|sealed\s+)*"
        r"(?:class|record|interface|enum)\s+(\w+)",
        source,
        re.MULTILINE,
    )
    return None if match is None else match.group(1)


def nested_command_classes(source: str, owner: str) -> set[str]:
    names: set[str] = set()
    command_annotation_seen = False
    for line in source.splitlines():
        if "@Command" in line:
            command_annotation_seen = True
        match = re.search(r"public\s+static\s+(?:final\s+)?class\s+(\w+)", line)
        if match and command_annotation_seen:
            names.add(f"{owner}${match.group(1)}")
            command_annotation_seen = False
    return names


def nested_static_classes(source: str, owner: str) -> set[str]:
    return {
        f"{owner}${match.group(1)}"
        for match in re.finditer(r"public\s+static\s+(?:final\s+)?class\s+(\w+)", source)
    }


if __name__ == "__main__":
    raise SystemExit(main())
