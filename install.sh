#!/bin/sh
# Fluxion installer.
#
#   curl --proto '=https' --tlsv1.2 --proto-redir '=https' -sSfL \
#     https://worxbend.github.io/fluxion/install.sh | sh
#
# Downloads the latest Fluxion release, verifies its SHA-256 checksum against the
# published checksum file, and installs the binary into a user-writable directory.
# Nothing is installed system-wide and no shell startup file is modified.

set -eu

REPO="worxbend/fluxion"
REPO_URL="https://github.com/${REPO}"

# Every download goes through these flags so a downgraded or plaintext connection
# fails instead of silently fetching an installer from somewhere else.
#
# Release downloads redirect to a CDN, so -L is required. --proto only constrains
# the first request; without --proto-redir, curl would still follow a redirect to
# plain http, which would defeat the point of pinning https here.
CURL_OPTS="--proto =https --tlsv1.2 --proto-redir =https -fsSL"

BIN_DIR="${FLUXION_BIN_DIR:-${HOME}/.local/bin}"
SHARE_DIR="${XDG_DATA_HOME:-${HOME}/.local/share}/fluxion"
REQUESTED_VERSION=""
ASSUME_YES=0

tmp_dir=""

log() { printf '%s\n' "$*"; }
info() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mwarning:\033[0m %s\n' "$*" >&2; }
err() {
  printf '\033[1;31merror:\033[0m %s\n' "$*" >&2
  exit 1
}

cleanup() {
  [ -n "${tmp_dir}" ] && [ -d "${tmp_dir}" ] && rm -rf "${tmp_dir}"
  return 0
}
trap cleanup EXIT INT TERM

usage() {
  cat <<'EOF'
Install Fluxion, a YAML-driven Linux workstation bootstrapper.

USAGE:
    install.sh [OPTIONS]

OPTIONS:
        --version <TAG>   Install a specific release (for example v1.0.0)
                          instead of the latest one.
        --bin-dir <DIR>   Directory to install the binary into.
                          Default: ~/.local/bin (or $FLUXION_BIN_DIR)
    -y, --yes             Do not prompt before overwriting an existing install.
    -h, --help            Print this message.

ENVIRONMENT:
    FLUXION_BIN_DIR   Same as --bin-dir.
    XDG_DATA_HOME     Base directory for example configs and docs.

The installer verifies the release SHA-256 checksum before installing and never
writes outside --bin-dir and the data directory.
EOF
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || err "required command not found: $1"
}

# curl is preferred, but a wget-only box is common enough on minimal images.
downloader=""
detect_downloader() {
  if command -v curl >/dev/null 2>&1; then
    downloader="curl"
  elif command -v wget >/dev/null 2>&1; then
    downloader="wget"
  else
    err "need either curl or wget to download Fluxion"
  fi
}

download() {
  # download <url> <destination>
  if [ "${downloader}" = "curl" ]; then
    # shellcheck disable=SC2086
    curl ${CURL_OPTS} -o "$2" "$1"
  else
    wget --https-only --secure-protocol=TLSv1_2 -qO "$2" "$1"
  fi
}

resolve_latest_tag() {
  # /releases/latest redirects to /releases/tag/<TAG>. Reading the redirect target
  # avoids the GitHub API, which rate-limits unauthenticated callers to 60/hour and
  # would make the installer fail intermittently on shared networks.
  _url=""
  if [ "${downloader}" = "curl" ]; then
    # shellcheck disable=SC2086
    _url=$(curl ${CURL_OPTS} -o /dev/null -w '%{url_effective}' "${REPO_URL}/releases/latest") || _url=""
  else
    _url=$(wget --https-only --secure-protocol=TLSv1_2 -qS --spider -O /dev/null \
      "${REPO_URL}/releases/latest" 2>&1 |
      sed -n 's/^[[:space:]]*Location:[[:space:]]*\([^[:space:]]*\).*/\1/p' | tail -n 1) || _url=""
  fi

  case "${_url}" in
    */releases/tag/*) printf '%s\n' "${_url##*/}" ;;
    *) return 1 ;;
  esac
}

detect_target() {
  _os=$(uname -s)
  _arch=$(uname -m)

  [ "${_os}" = "Linux" ] ||
    err "Fluxion publishes Linux builds only (detected ${_os}).
       The runnable JAR works anywhere with Java 25:
       ${REPO_URL}/releases/latest"

  case "${_arch}" in
    x86_64 | amd64) printf 'linux-amd64\n' ;;
    *)
      err "no prebuilt binary for ${_os} ${_arch}.
       The runnable JAR works on any architecture with Java 25:
       ${REPO_URL}/releases/latest"
      ;;
  esac
}

verify_checksum() {
  # verify_checksum <file> <checksums-file>
  _file_name=$(basename "$1")
  _expected=$(awk -v name="${_file_name}" '$2 == name || $2 == "*" name { print $1 }' "$2" | head -n 1)
  [ -n "${_expected}" ] || err "no checksum published for ${_file_name}"

  if command -v sha256sum >/dev/null 2>&1; then
    _actual=$(sha256sum "$1" | awk '{ print $1 }')
  elif command -v shasum >/dev/null 2>&1; then
    _actual=$(shasum -a 256 "$1" | awk '{ print $1 }')
  else
    err "need sha256sum or shasum to verify the download"
  fi

  [ "${_actual}" = "${_expected}" ] ||
    err "checksum mismatch for ${_file_name}
       expected ${_expected}
       actual   ${_actual}
       Refusing to install. Please report this at ${REPO_URL}/issues"

  info "Checksum verified (${_expected})"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --version)
      [ $# -ge 2 ] || err "--version needs a value"
      REQUESTED_VERSION="$2"
      shift 2
      ;;
    --version=*)
      REQUESTED_VERSION="${1#*=}"
      shift
      ;;
    --bin-dir)
      [ $# -ge 2 ] || err "--bin-dir needs a value"
      BIN_DIR="$2"
      shift 2
      ;;
    --bin-dir=*)
      BIN_DIR="${1#*=}"
      shift
      ;;
    -y | --yes)
      ASSUME_YES=1
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *) err "unknown option: $1 (try --help)" ;;
  esac
done

need_cmd uname
need_cmd tar
need_cmd awk
detect_downloader

target=$(detect_target)

if [ -n "${REQUESTED_VERSION}" ]; then
  tag="${REQUESTED_VERSION}"
  case "${tag}" in
    v*) ;;
    *) tag="v${tag}" ;;
  esac
else
  info "Resolving the latest release..."
  tag=$(resolve_latest_tag) ||
    err "could not determine the latest release.
       Check ${REPO_URL}/releases or pass --version <TAG>"
fi

info "Installing Fluxion ${tag} (${target})"

tarball="fluxion-${tag}-${target}.tar.gz"
checksums="fluxion-${tag}-checksums.sha256"
base_url="${REPO_URL}/releases/download/${tag}"

tmp_dir=$(mktemp -d)

info "Downloading ${tarball}"
download "${base_url}/${tarball}" "${tmp_dir}/${tarball}" ||
  err "failed to download ${tarball}
       Does ${tag} exist? See ${REPO_URL}/releases"

download "${base_url}/${checksums}" "${tmp_dir}/${checksums}" ||
  err "failed to download ${checksums} — refusing to install unverified bytes"

verify_checksum "${tmp_dir}/${tarball}" "${tmp_dir}/${checksums}"

mkdir -p "${tmp_dir}/unpacked"
tar -xzf "${tmp_dir}/${tarball}" -C "${tmp_dir}/unpacked"

[ -f "${tmp_dir}/unpacked/fluxion" ] || err "release archive did not contain a fluxion binary"

if [ -e "${BIN_DIR}/fluxion" ] && [ "${ASSUME_YES}" -eq 0 ] && [ -t 0 ]; then
  printf 'Replace the existing %s? [y/N] ' "${BIN_DIR}/fluxion"
  read -r reply
  case "${reply}" in
    y | Y | yes | YES) ;;
    *) err "aborted" ;;
  esac
fi

mkdir -p "${BIN_DIR}"
# Install to a temporary name first so an interrupted copy cannot leave a
# half-written binary on PATH, then rename atomically.
install_tmp="${BIN_DIR}/.fluxion.$$"
cp "${tmp_dir}/unpacked/fluxion" "${install_tmp}"
chmod 755 "${install_tmp}"
mv -f "${install_tmp}" "${BIN_DIR}/fluxion"

mkdir -p "${SHARE_DIR}"
for extra in config docs; do
  if [ -d "${tmp_dir}/unpacked/${extra}" ]; then
    rm -rf "${SHARE_DIR:?}/${extra}"
    cp -R "${tmp_dir}/unpacked/${extra}" "${SHARE_DIR}/${extra}"
  fi
done

info "Installed ${BIN_DIR}/fluxion"
log "    example profiles: ${SHARE_DIR}/config"
log "    documentation:    ${SHARE_DIR}/docs"

case ":${PATH}:" in
  *":${BIN_DIR}:"*)
    log ""
    info "Done. Try:"
    log "    fluxion --help"
    log "    fluxion validate -c ${SHARE_DIR}/config/example-fedora.yaml"
    ;;
  *)
    log ""
    warn "${BIN_DIR} is not on your PATH."
    log "Add it by appending this line to your shell profile:"
    log ""
    log "    export PATH=\"${BIN_DIR}:\$PATH\""
    log ""
    log "Then reopen your shell and run: fluxion --help"
    ;;
esac
