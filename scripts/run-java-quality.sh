#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}/sysboot"

./mill __.compile

source_roots=(core/src config-parser/src executor/src tui/src app/src cli/src)
class_roots=(
  out/core/compile.dest/classes
  out/config-parser/compile.dest/classes
  out/executor/compile.dest/classes
  out/tui/compile.dest/classes
  out/app/compile.dest/classes
  out/cli/compile.dest/classes
)

./mill quality.runMain com.puppycrawl.tools.checkstyle.Main \
  -c /google_checks.xml \
  -p config/checkstyle-google.properties \
  "${source_roots[@]}"

./mill quality.runMain com.puppycrawl.tools.checkstyle.Main \
  -c config/checkstyle.xml "${source_roots[@]}"

if ./mill quality.runMain com.puppycrawl.tools.checkstyle.Main \
  -c /google_checks.xml \
  -p config/checkstyle-google.properties \
  config/quality-fixtures/InvalidGoogleName.java >/dev/null 2>&1; then
  echo "Google Checkstyle self-test unexpectedly accepted an invalid field name" >&2
  exit 1
fi

./mill quality.runMain net.sourceforge.pmd.cli.PmdCli check \
  -d core/src \
  -d config-parser/src \
  -d executor/src \
  -d tui/src \
  -d app/src \
  -d cli/src \
  -R config/pmd-ruleset.xml \
  -f text \
  --no-cache

aux_classpath="$(
  ./mill show cli.compileClasspath |
    sed -n 's#.*:\(/[^"[:space:]]*\)".*#\1#p' |
    paste -sd: -
)"
if [[ -z "${aux_classpath}" ]]; then
  echo "Unable to resolve the SpotBugs auxiliary classpath" >&2
  exit 1
fi

./mill quality.runMain edu.umd.cs.findbugs.LaunchAppropriateUI \
  -textui \
  -effort:max \
  -medium \
  -exitcode \
  -exclude config/spotbugs-exclude.xml \
  -auxclasspath "${aux_classpath}" \
  -onlyAnalyze 'dev.sysboot.-' \
  "${class_roots[@]}"
