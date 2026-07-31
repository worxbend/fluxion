#!/usr/bin/env python3

from __future__ import annotations

import re
import sys
from pathlib import Path

ACTION_SHA = re.compile(r"^[0-9a-f]{40}$")
PIN_COMMENT = re.compile(r"#\s+v[0-9][0-9A-Za-z.-]*\s*$")
RUN_KEY = re.compile(r"^(?P<indent>\s*)(?:-\s+)?run:\s*(?P<value>.*)$")
MILL_DEPENDENCY = re.compile(
    r"^\s*-\s+(?P<group>[^:\s]+):(?P<artifact>[^:\s]+):(?P<version>[^\s]+)\s*$"
)

MINIMUM_DEPENDENCY_VERSIONS = {
    ("com.fasterxml.jackson.core", "jackson-databind"): (2, 21, 2),
    ("com.fasterxml.jackson.dataformat", "jackson-dataformat-yaml"): (2, 21, 2),
    ("org.apache.commons", "commons-compress"): (1, 28, 0),
    ("org.slf4j", "slf4j-api"): (2, 0, 18),
    ("ch.qos.logback", "logback-classic"): (1, 5, 34),
}


def check_action_pins(path: Path, text: str) -> list[str]:
    errors = []
    for number, line in enumerate(text.splitlines(), 1):
        if "uses:" not in line:
            continue
        value = line.split("uses:", 1)[1].split("#", 1)[0].strip()
        if value.startswith("./"):
            continue
        reference = value.rsplit("@", 1)[-1]
        if not ACTION_SHA.fullmatch(reference):
            errors.append(f"{path}:{number}: action is not pinned to a commit SHA")
        elif not PIN_COMMENT.search(line):
            errors.append(f"{path}:{number}: pinned action is missing its version comment")
    return errors


def check_run_expressions(path: Path, text: str) -> list[str]:
    errors = []
    lines = text.splitlines()
    for index, line in enumerate(lines):
        match = RUN_KEY.match(line)
        if match is None:
            continue
        value = match.group("value")
        if value not in {"|", "|-", ">", ">-"}:
            if "${{" in value:
                errors.append(f"{path}:{index + 1}: expression appears in a run command")
            continue
        run_indent = len(match.group("indent"))
        for offset in range(index + 1, len(lines)):
            candidate = lines[offset]
            if candidate and len(candidate) - len(candidate.lstrip()) <= run_indent:
                break
            if "${{" in candidate:
                errors.append(f"{path}:{offset + 1}: expression appears in a run script")
    return errors


def check_read_only_default(path: Path, text: str) -> list[str]:
    if re.search(r"(?m)^permissions:\n  contents: read(?:\n|$)", text):
        return []
    return [f"{path}: workflow must default to contents: read"]


def check_release_policy(path: Path, text: str) -> list[str]:
    requirements = {
        "release has an initial policy job": "  policy:\n    name: Verify Release Policy",
        "release build depends on policy and trusted bytes": (
            "    needs: [policy, known-tool-bytes]"
        ),
        "release policy runs the repository checker": "python3 scripts/check_ci_policy.py",
        "release verifies trusted tool bytes": 'SYSBOOT_VERIFY_KNOWN_TOOL_BYTES: "true"',
        "publish job has scoped write permission": (
            "    permissions:\n      actions: read\n      contents: write"
        ),
        "build exports its exact commit": "commit: ${{ steps.commit.outputs.commit }}",
        "tag is resolved to a commit": 'git rev-parse "refs/tags/${tag}^{commit}"',
        "existing tag is compared with the build commit": (
            'if [ "$tagged_commit" != "$requested_commit" ]; then'
        ),
        "release targets the build commit": (
            "target_commitish: ${{ needs.build.outputs.commit }}"
        ),
        "release version is read from the canonical source": (
            "sysboot/core/src/dev/sysboot/core/FluxionVersion.java"
        ),
        "JVM assembly is smoke tested": (
            "java -jar sysboot/out/cli/assembly.dest/out.jar --version"
        ),
        "native generate path is smoke tested": (
            '"$executable" generate --os debian --profile smoke'
        ),
        "native doctor path is smoke tested": '"$executable" doctor --skip-network',
        "release runs Java static analysis": "./scripts/run-java-quality.sh",
        "release archive has normalized ordering": "tar --sort=name",
        "release gzip omits timestamps": "gzip -n",
    }
    return [
        f"{path}: missing policy: {description}"
        for description, marker in requirements.items()
        if marker not in text
    ]


def check_ci_artifact_policy(path: Path, text: str) -> list[str]:
    requirements = {
        "trusted-tool byte verification is required": (
            "  known-tool-bytes:\n    name: Verify Trusted Tool Bytes"
        ),
        "trusted-tool verification cannot silently disable itself": (
            'SYSBOOT_VERIFY_KNOWN_TOOL_BYTES: "true"'
        ),
        "JVM assembly is smoke tested": (
            "java -jar sysboot/out/cli/assembly.dest/out.jar --version"
        ),
        "native generate path is smoke tested": (
            '"$executable" generate --os debian --profile smoke'
        ),
        "native doctor path is smoke tested": '"$executable" doctor --skip-network',
        "CI runs Java static analysis": "just quality-check",
        "native archive has normalized ordering": "tar --sort=name",
        "native gzip omits timestamps": "gzip -n",
    }
    return [
        f"{path}: missing artifact policy: {description}"
        for description, marker in requirements.items()
        if marker not in text
    ]


def check_mill_policy(path: Path, text: str, version: str) -> list[str]:
    requirements = {
        "launcher version matches .mill-version": f'PINNED_MILL_VERSION="{version}"',
        "download uses HTTPS-only curl": "curl --proto '=https' --tlsv1.2",
        "download is verified": 'verify_mill_artifact "${MILL_TEMP_DOWNLOAD_FILE}"',
        "cached launcher is verified": 'verify_mill_artifact "${MILL}"',
    }
    errors = [
        f"{path}: missing policy: {description}"
        for description, marker in requirements.items()
        if marker not in text
    ]
    digests = set(re.findall(r'echo "([0-9a-f]{64})"', text))
    if len(digests) != 5:
        errors.append(f"{path}: expected five platform-specific SHA-256 digests")
    return errors


def check_formatter_policy(path: Path, text: str) -> list[str]:
    requirements = {
        "formatter download uses HTTPS-only curl": "curl --proto '=https' --tlsv1.2",
        "formatter has a pinned SHA-256 digest": (
            'gjf_sha256 := "bfb7f9ead6cd328389bc2da53860443bc'
            '0e805dfd08cc889bfdf43b26cb2a6e8"'
        ),
        "cached formatter is verified": (
            'echo "{{gjf_sha256}}  {{gjf_jar}}" | sha256sum -c -'
        ),
        "downloaded formatter is verified before replacement": (
            'echo "{{gjf_sha256}}  $formatter_temp" | sha256sum -c -'
        ),
    }
    return [
        f"{path}: missing policy: {description}"
        for description, marker in requirements.items()
        if marker not in text
    ]


def check_dependency_policy(paths: list[Path], build_text: str) -> list[str]:
    errors = []
    if "snapshot" in build_text.lower():
        errors.append("sysboot/build.mill.yaml: snapshot repositories are not permitted")
    found = {}
    for path in paths:
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            match = MILL_DEPENDENCY.match(line)
            if match is None:
                continue
            coordinate = (match.group("group"), match.group("artifact"))
            version = match.group("version")
            lowered = version.lower()
            if (
                "snapshot" in lowered
                or lowered in {"latest", "release"}
                or any(value in version for value in ("+", "[", "]", "(", ")"))
            ):
                errors.append(f"{path}:{number}: dependency version must be an immutable release")
            found[coordinate] = (path, number, version)
    for coordinate, minimum in MINIMUM_DEPENDENCY_VERSIONS.items():
        if coordinate not in found:
            errors.append(f"missing required dependency policy target: {':'.join(coordinate)}")
            continue
        path, number, version = found[coordinate]
        parsed = numeric_version(version)
        if parsed is None or parsed < minimum:
            floor = ".".join(map(str, minimum))
            errors.append(
                f"{path}:{number}: {':'.join(coordinate)} must be at least {floor}"
            )
    return errors


def numeric_version(value: str) -> tuple[int, ...] | None:
    if not re.fullmatch(r"\d+(?:\.\d+)*", value):
        return None
    return tuple(int(part) for part in value.split("."))


def dependency_files(root: Path) -> list[Path]:
    return sorted((root / "sysboot").rglob("package.mill.yaml"))


def check_repository(root: Path) -> list[str]:
    errors = []
    workflows = sorted((root / ".github" / "workflows").glob("*.y*ml"))
    for workflow in workflows:
        text = workflow.read_text(encoding="utf-8")
        errors.extend(check_action_pins(workflow, text))
        errors.extend(check_run_expressions(workflow, text))
        errors.extend(check_read_only_default(workflow, text))

    release = root / ".github" / "workflows" / "release.yml"
    errors.extend(check_release_policy(release, release.read_text(encoding="utf-8")))
    ci = root / ".github" / "workflows" / "ci.yml"
    errors.extend(check_ci_artifact_policy(ci, ci.read_text(encoding="utf-8")))

    mill = root / "sysboot" / "mill"
    version = (root / "sysboot" / ".mill-version").read_text(encoding="utf-8").strip()
    errors.extend(check_mill_policy(mill, mill.read_text(encoding="utf-8"), version))
    justfile = root / "justfile"
    errors.extend(check_formatter_policy(justfile, justfile.read_text(encoding="utf-8")))
    build_file = root / "sysboot" / "build.mill.yaml"
    errors.extend(
        check_dependency_policy(
            dependency_files(root), build_file.read_text(encoding="utf-8")
        )
    )
    return errors


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    errors = check_repository(root)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("CI policy check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
