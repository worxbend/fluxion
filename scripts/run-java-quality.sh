#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}/sysboot"

# Each analyser runs through run_tool so a failure prints the tool's own output
# and its exit status. Without this the gate fails as a bare "Subprocess failed"
# and the findings — the only thing that explains the failure — are lost.
report_dir="${TMPDIR:-/tmp}/fluxion-quality.$$"
mkdir -p "${report_dir}"
trap 'rm -rf "${report_dir}"' EXIT

run_tool() {
  local label="$1"
  shift
  local log_file="${report_dir}/${label}.log"
  local rc=0

  "$@" >"${log_file}" 2>&1 || rc=$?

  if [[ ${rc} -ne 0 ]]; then
    echo "::group::${label} output"
    cat "${log_file}"
    # Analysers that write a report file get it printed too. PMD in particular
    # warns that reporting to stdout is unreliable and asks for -r, so its
    # findings can be absent from the captured stream entirely.
    local report_file="${report_dir}/${label}-report.txt"
    if [[ -s ${report_file} ]]; then
      echo "--- ${label} report ---"
      cat "${report_file}"
    else
      echo "--- ${label} produced no report file ---"
    fi
    echo "::endgroup::"
    echo "${label} failed with exit code ${rc}" >&2
    # An analyser killed by the kernel (128 + signal) has no findings to report,
    # so say that outright instead of letting it read as a code violation.
    if [[ ${rc} -gt 128 ]]; then
      echo "${label} was terminated by signal $((rc - 128)) — this is a runner" \
        "resource problem, not a finding." >&2
    fi
    exit "${rc}"
  fi

  echo "${label}: ok"
}

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

run_tool checkstyle-google \
  ./mill quality.runMain com.puppycrawl.tools.checkstyle.Main \
  -c /google_checks.xml \
  -p config/checkstyle-google.properties \
  "${source_roots[@]}"

run_tool checkstyle-repo \
  ./mill quality.runMain com.puppycrawl.tools.checkstyle.Main \
  -c config/checkstyle.xml "${source_roots[@]}"

if ./mill quality.runMain com.puppycrawl.tools.checkstyle.Main \
  -c /google_checks.xml \
  -p config/checkstyle-google.properties \
  config/quality-fixtures/InvalidGoogleName.java >/dev/null 2>&1; then
  echo "Google Checkstyle self-test unexpectedly accepted an invalid field name" >&2
  exit 1
fi
echo "checkstyle-selftest: ok"

run_tool pmd \
  ./mill quality.runMain net.sourceforge.pmd.cli.PmdCli check \
  -d core/src \
  -d config-parser/src \
  -d executor/src \
  -d tui/src \
  -d app/src \
  -d cli/src \
  -R config/pmd-ruleset.xml \
  -f text \
  -r "${report_dir}/pmd-report.txt" \
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

run_tool spotbugs \
  ./mill quality.runMain edu.umd.cs.findbugs.LaunchAppropriateUI \
  -textui \
  -effort:max \
  -medium \
  -exitcode \
  -exclude config/spotbugs-exclude.xml \
  -output "${report_dir}/spotbugs-report.txt" \
  -auxclasspath "${aux_classpath}" \
  -onlyAnalyze 'dev.sysboot.-' \
  "${class_roots[@]}"
