#!/usr/bin/env python3

from __future__ import annotations

import unittest
from tempfile import TemporaryDirectory
from pathlib import Path

import check_ci_policy


class CiPolicyTest(unittest.TestCase):
    def test_action_tag_is_rejected(self) -> None:
        errors = check_ci_policy.check_action_pins(
            Path("ci.yml"), "      - uses: actions/checkout@v4\n"
        )

        self.assertEqual(1, len(errors))

    def test_pinned_action_with_version_comment_is_accepted(self) -> None:
        workflow = (
            "      - uses: actions/checkout@"
            "11d5960a326750d5838078e36cf38b85af677262 # v4\n"
        )

        self.assertEqual(
            [], check_ci_policy.check_action_pins(Path("ci.yml"), workflow)
        )

    def test_expression_in_multiline_run_script_is_rejected(self) -> None:
        workflow = "      - run: |\n          echo '${{ inputs.version }}'\n"

        errors = check_ci_policy.check_run_expressions(Path("release.yml"), workflow)

        self.assertEqual(1, len(errors))

    def test_release_must_compare_existing_tag_with_build_commit(self) -> None:
        errors = check_ci_policy.check_release_policy(Path("release.yml"), "")

        self.assertTrue(
            any("existing tag is compared with the build commit" in error for error in errors)
        )

    def test_release_version_gate_must_use_canonical_source(self) -> None:
        errors = check_ci_policy.check_release_policy(
            Path("release.yml"),
            "sysboot/cli/src/dev/sysboot/cli/command/VersionProvider.java",
        )

        self.assertTrue(
            any("canonical source" in error for error in errors)
        )

    def test_release_build_must_depend_on_policy_and_trusted_bytes(self) -> None:
        errors = check_ci_policy.check_release_policy(
            Path("release.yml"),
            "  policy:\n    name: Verify Release Policy\n",
        )

        self.assertTrue(any("depends on policy" in error for error in errors))
        self.assertTrue(any("trusted tool bytes" in error for error in errors))

    def test_ci_requires_distributable_smokes_and_trusted_bytes(self) -> None:
        errors = check_ci_policy.check_ci_artifact_policy(Path("ci.yml"), "")

        self.assertTrue(any("trusted-tool byte verification" in error for error in errors))
        self.assertTrue(any("JVM assembly" in error for error in errors))
        self.assertTrue(any("native generate" in error for error in errors))
        self.assertTrue(any("native doctor" in error for error in errors))
        self.assertTrue(any("Java static analysis" in error for error in errors))
        self.assertTrue(any("normalized ordering" in error for error in errors))

    def test_launcher_requires_all_platform_digests(self) -> None:
        launcher = (
            'PINNED_MILL_VERSION="1.1.6"\n'
            "curl --proto '=https' --tlsv1.2\n"
            'verify_mill_artifact "${MILL_TEMP_DOWNLOAD_FILE}"\n'
            'verify_mill_artifact "${MILL}"\n'
            'echo "' + ("a" * 64) + '"\n'
        )

        errors = check_ci_policy.check_mill_policy(
            Path("mill"), launcher, "1.1.6"
        )

        self.assertTrue(any("five platform-specific" in error for error in errors))

    def test_formatter_download_requires_pinned_digest_and_verification(self) -> None:
        errors = check_ci_policy.check_formatter_policy(Path("justfile"), "")

        self.assertTrue(any("HTTPS-only curl" in error for error in errors))
        self.assertTrue(any("pinned SHA-256" in error for error in errors))
        self.assertTrue(any("cached formatter" in error for error in errors))
        self.assertTrue(any("before replacement" in error for error in errors))

    def test_dependency_policy_rejects_snapshot_and_old_security_floor(self) -> None:
        with TemporaryDirectory() as directory:
            package = Path(directory) / "package.mill.yaml"
            package.write_text(
                "\n".join(
                    [
                        "- com.fasterxml.jackson.core:jackson-databind:2.17.2",
                        "- com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.21.2",
                        "- org.apache.commons:commons-compress:1.28.0",
                        "- org.slf4j:slf4j-api:2.0.18",
                        "- ch.qos.logback:logback-classic:1.5.34-SNAPSHOT",
                    ]
                ),
                encoding="utf-8",
            )

            errors = check_ci_policy.check_dependency_policy(
                [package], "mill-repositories: maven-snapshots"
            )

        self.assertTrue(any("snapshot repositories" in error for error in errors))
        self.assertTrue(any("jackson-databind must be at least" in error for error in errors))
        self.assertTrue(any("immutable release" in error for error in errors))

    def test_dependency_policy_rejects_dynamic_and_parenthesized_versions(self) -> None:
        with TemporaryDirectory() as directory:
            package = Path(directory) / "package.mill.yaml"
            package.write_text(
                "\n".join(
                    [
                        "- example:latest:LATEST",
                        "- example:release:RELEASE",
                        "- example:range:(1.0,2.0)",
                    ]
                ),
                encoding="utf-8",
            )

            errors = check_ci_policy.check_dependency_policy([package], "")

        immutable_errors = [error for error in errors if "immutable release" in error]
        self.assertEqual(3, len(immutable_errors))

    def test_repository_dependency_scan_includes_nested_test_modules(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            top_level = root / "sysboot" / "core" / "package.mill.yaml"
            nested = root / "sysboot" / "core" / "test" / "package.mill.yaml"
            top_level.parent.mkdir(parents=True)
            nested.parent.mkdir(parents=True)
            top_level.touch()
            nested.touch()

            found = check_ci_policy.dependency_files(root)

        self.assertEqual([top_level, nested], found)


if __name__ == "__main__":
    unittest.main()
