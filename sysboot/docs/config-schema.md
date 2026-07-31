# Config Schema Reference

All config files are YAML. Place them in `~/.config/fluxion/` or pass with `-c`.

---

## Stable jobs/steps schema

The stable Fluxion config schema is the `profile`/`os`/`jobs` form with `steps` inside each job.
Use this schema when you want an explicit job DAG with `dependsOn` ordering.

| Field | Type | Required | Description |
|---|---|---|---|
| `profile` | string | yes | Unique name for this profile (no spaces) |
| `os` | object | yes | OS target descriptor |
| `jobs` | list | no | Workflow-style DAG of bootstrap jobs. Preferred schema. |
| `phases` | list | no | Legacy alias for `jobs`. |
| `modules` | list | no | Legacy ordered module list. Used only when `jobs` and `phases` are absent. |

At least one job, phase, or legacy module is required. When `jobs` is present and non-empty,
`phases` and top-level `modules` are ignored. When `phases` is present and non-empty, top-level
`modules` is ignored.

---

## Schema compatibility

Fluxion supports two config frontends:

- Stable jobs/steps: `profile`, `os`, and `jobs[].steps[]`. This remains the stable DAG-oriented
  schema and is documented on this page.
- WorkstationProfile manifests: `apiVersion`, `kind: WorkstationProfile`, `metadata`, and `spec`.
  This is the newer manifest frontend for ordered workstation plans. See
  [workstation-profile.md](workstation-profile.md).

Legacy `phases` and flat top-level `modules` configs also remain supported for compatibility:
`phases` is treated as an alias for `jobs`, and top-level `modules` is used only when neither
`jobs` nor `phases` is present.

The currently accepted WorkstationProfile `apiVersion` string is `initkit.io/v1alpha1` as a
compatibility identifier. Fluxion is the product and command name; use `fluxion validate`,
`fluxion plan`, `fluxion dry-run`, and `fluxion apply` for both schemas.

Minimal WorkstationProfile manifest:

```yaml
apiVersion: initkit.io/v1alpha1
kind: WorkstationProfile
metadata:
  name: workstation
spec:
  target:
    os:
      distribution: fedora
      release: "44"
  plan:
    - name: core-cli
      kind: dnf-packages
      when:
        distribution: fedora
      spec:
        packages: [git, curl]
```

For manifests, `spec.target.os` is informational metadata used to map the manifest into Fluxion's
core target model and validation reports. It does not decide which plan entries run. Host facts and
per-entry `when` rules drive selected and skipped WorkstationProfile work.
Supported `when` matchers are documented in `workstation-profile.md`; reserved `files`, `vars`, and
`expression` guards are rejected rather than silently treated as true. Variable substitutions in
shell-expression fields are POSIX-quoted as inert data.

---

## `os` object

| Field | Type | Required | Values |
|---|---|---|---|
| `type` | string | yes | `fedora`, `arch`, `opensuse`, `debian` |
| `release` | string | no | OS release string, e.g. `"41"` for Fedora |

---

## `jobs` list

```yaml
jobs:
  - name: shell-foundation        # required, unique within profile
    description: "Set login shell"
    dependsOn: [dotfiles]         # optional, defaults to []
    continueOnModuleError: true   # default: true
    restartPolicy:
      type: prompt-logout
      message: "Log out and back in, then re-run fluxion."
    steps:
      - type: packages
        name: shell-tools
        packageManager: dnf
        packages: [zsh]
```

Jobs are topologically sorted by `dependsOn`. A job with `continueOnModuleError: false` hard-fails
on the first failed step and blocks dependent jobs. With `continueOnModuleError: true`, failed
items are reported but the job can complete and dependents can continue.

`phases` is accepted as an alias for `jobs`, and `modules` is accepted as an alias for `steps`
inside each job for older configs.

### `restartPolicy`

```yaml
restartPolicy:
  type: none
```

```yaml
restartPolicy:
  type: prompt-logout
  message: "Log out and back in, then re-run fluxion."
```

```yaml
restartPolicy:
  type: requires-new-shell
  shell: zsh                      # zsh | bash | sh
```

`prompt-logout` records completed state, emits a restart-required event, and stops execution so the
user can log out and resume deterministically. `requires-new-shell` runs later phase effects through
a fresh login shell wrapper so tools installed into shell startup paths are visible.

## Module types

### `packages` — install system packages

```yaml
- type: packages
  name: core-cli-tools          # required, unique within profile
  packageManager: dnf           # required: dnf | pacman | paru | yay | apt | zypper
  continueOnError: true         # default: true — continue if one package fails
  packages:                     # required, ≥1 item
    - git
    - curl
```

Each package is installed in a **separate process** so one failure never blocks others.

Package names are validated to reject shell metacharacters (space, `$`, `;`, `|`, `&`, `` ` ``, `>`, `<`).
`fluxion validate` also checks that the selected package manager matches the configured OS target:
Fedora uses `dnf`, Arch uses `pacman`, `paru`, or `yay`, Debian uses `apt`, and openSUSE uses
`zypper`. Duplicate package names are reported as warnings.

---

### `flatpak` — install Flatpak applications

```yaml
- type: flatpak
  name: desktop-apps            # required
  remote: flathub               # default: flathub
  appIds:                       # required, ≥1 item
    - com.spotify.Client
    - org.telegram.desktop
```

Use `flatpak-remote` when the remote itself should be declared and audited instead of hidden in a
shell command.

Use `apt-repository` for Debian/Ubuntu APT sources that would otherwise be hidden in shell setup
commands.

Use `rpm-repository` for Fedora DNF repository files that would otherwise be hidden in shell setup
commands.

Use `pacman-repository` for Arch Pacman repository blocks that would otherwise be hidden in shell
setup commands.

Repository steps and generated `WorkstationProfile` entries under `spec.sources` share one trust
contract. Every source URI must be HTTPS without user-info, including the URI embedded in APT
`deb` and `deb-src` lines. APT `signingKeyUrl` and DNF/Zypper `gpgKeyUrl` values require a SHA-256
`checksum` over the exact remote key response bytes. Flatpak source URLs always require a SHA-256
`checksum` over the exact `.flatpakrepo` response bytes. Verification completes before any
privileged key, repository, or configuration mutation. A repository `baseUrl` is
transport-validated but is not itself a finite checksum subject. Programmatic repository modules
without a remote key remain supported; YAML rejects a key URL without its checksum and a checksum
without its key URL.

---

### `apt-repository` — add an APT source

```yaml
- type: apt-repository
  name: docker
  source: deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian bookworm stable
  sourceList: /etc/apt/sources.list.d/docker.list # default: /etc/apt/sources.list.d/<name>.list
  signingKeyUrl: https://download.docker.com/linux/debian/gpg
  keyring: /etc/apt/keyrings/docker.gpg          # default when signingKeyUrl is set
  checksum:
    algorithm: sha256
    value: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

Fluxion downloads and verifies a declared signing key without privileges, dearmors it without
privileges, then uses structured `sudo install` commands for the keyring and source list before
running `sudo apt-get update`. `plan --show-commands`, `dry-run`, `status`, `diff`, and `explain`
use the source list path as the item key. Validation fails when the target OS is not Debian/Ubuntu,
when any source or key URL is not HTTPS or contains user-info, or when a key URL and checksum are
not configured together. Source options are restricted to `arch` and exactly one `signed-by`;
`signed-by` must match the absolute `keyring` path. Trust-bypass and alternate option syntax,
including `trusted` and `allow-insecure`, is rejected.
`sourceList` is confined to direct `.list` files in `/etc/apt/sources.list.d`. Keyrings are
normalized and confined to `.gpg` or `.asc` files in `/etc/apt/keyrings` or
`/usr/share/keyrings`.

---

### `rpm-repository` — add a Fedora DNF repository

```yaml
- type: rpm-repository
  name: docker
  id: docker
  baseUrl: https://download.docker.com/linux/fedora/$releasever/$basearch/stable
  repoFile: /etc/yum.repos.d/docker.repo # default: /etc/yum.repos.d/<name>.repo
  gpgKeyUrl: https://download.docker.com/linux/fedora/gpg
  enabled: true                          # default: true
  gpgCheck: true                         # default: true
  checksum:
    algorithm: sha256
    value: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

Fluxion downloads and verifies a declared key without privileges, parses it without privileges,
installs the local key and an auditable `.repo` file with structured `sudo install` commands, and
refreshes metadata with `sudo dnf makecache --refresh`. The generated repository refers to the
installed local key, never the remote key URL. `plan --show-commands`, `dry-run`, `status`, `diff`,
and `explain` use the repo file path as the item key. Validation fails when the target OS is not
Fedora, when any URL is not HTTPS or contains user-info, when an enabled repository disables
`gpgCheck`, when `gpgCheck` lacks a key URL, or when a key URL and checksum are not configured
together.
`repoFile` must be a direct `.repo` file in `/etc/yum.repos.d`.

---

### `pacman-repository` — add an Arch Pacman repository

```yaml
- type: pacman-repository
  name: chaotic-aur
  repository: chaotic-aur                  # default: name
  server: https://cdn-mirror.chaotic.cx/$repo/$arch
  config: /etc/pacman.conf                 # default: /etc/pacman.conf
  sigLevel: Required TrustedOnly
  include: /etc/pacman.d/chaotic-mirrorlist
  enabled: true                            # default: true
```

Execution probes the repository header with structured `grep` arguments. When the repository is
missing, Fluxion reads only a bounded, root-owned, non-symbolic config beneath secure privileged
directories, stages the complete replacement privately, and installs it with a structured
`sudo install` command before `sudo pacman -Sy`. The config path is exactly `/etc/pacman.conf`;
optional includes are normalized direct children of `/etc/pacman.d`. `sigLevel` accepts only
Pacman's documented trust-policy tokens. Enabled repositories must leave both package and database
verification effectively `Required TrustedOnly`; validation applies tokens in order, including
later package/database-specific overrides. `plan --show-commands`, `dry-run`, `status`, `diff`, and
`explain` use the repository name as the item key. Validation fails when the target OS is not Arch,
when the server is not HTTPS or contains user-info, or when an enabled repository omits or weakens
`sigLevel`.

---

### `flatpak-remote` — add a Flatpak remote

```yaml
- type: flatpak-remote
  name: flathub                 # required
  remote: flathub               # required
  url: https://flathub.org/repo/flathub.flatpakrepo # required
  system: true                  # default: true; false adds the user remote
  checksum:
    algorithm: sha256
    value: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

Fluxion downloads and verifies the descriptor before passing its local path to
`flatpak remote-add --if-not-exists`. The remote URL is never passed to Flatpak. When `system` is
`false`, Fluxion adds `--user`. `plan --show-commands`, `dry-run`, `status`, `diff`, and `explain`
use the remote name as the item key. Validation requires the checksum and an HTTPS URL without
user-info.

---

### `shell-script` — run a shell script

```yaml
- type: shell-script
  name: install-sdkman          # required
  script: scripts/sdkman.sh     # local path, relative to config file or absolute
  args:                         # optional, default: []
    - --sdkman
  workingDir: /tmp              # optional, default: config file directory
  continueOnError: false        # default: false
```

Define exactly one of `script` or `url`. A local `script` is an operator-controlled filesystem
input and does not take an integrity field. A remote `url` must use HTTPS without user-info and
requires `sha256`:

```yaml
- type: shell-script
  name: install-sdkman
  url: https://example.org/install.sh
  sha256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

The same contract applies to `WorkstationProfile` `shell-scripts` entries. Each object containing
`url` must contain the SHA-256 of the exact response bytes. Fluxion downloads with bounded streaming
I/O, verifies the digest before execution (including `sudo` execution), and removes the temporary
file afterward. Redirects may remain on HTTPS, but the final URL must not contain user-info.

The interpreter is detected from the shebang line; falls back to `/bin/bash`. The script is run in a PTY so sudo prompts are handled by the TUI.

---

### `compiled-binary` — download and install a pre-built binary

```yaml
- type: compiled-binary
  name: install-neovim          # required
  binaryName: nvim              # required — display name and extracted file name
  url: https://github.com/...   # required — https only
  checksum:                     # required unless using a signer-bound detached signature
    algorithm: sha256           # SHA-256 is the only currently supported algorithm
    value: abc123...            # lowercase hex digest
  checksumUrl: https://github.com/.../checksums.txt # supplemental metadata; not a trust anchor
  signatureUrl: https://github.com/.../nvim.tar.gz.asc # optional detached signature
  allowedSignerFingerprint: 0123456789ABCDEF0123456789ABCDEF01234567 # required with signatureUrl
  installPath: /usr/local/bin/nvim  # required — absolute, normalized path
  archivePath: nvim-linux64/bin/nvim # required for archives — exact post-strip member path
  stripComponents: 1          # optional — path components stripped before matching archivePath
  mode: "0755"                # optional — POSIX install mode; default: "0755"
  symlinkPath: /usr/local/bin/vim # optional — symlink pointing to installPath
  continueOnError: false        # default: false
```

Every compiled binary must use one of these trust modes:

- a literal SHA-256 `checksum`;
- an HTTPS detached `signatureUrl` together with the explicitly trusted
  `allowedSignerFingerprint`.

`checksumUrl` is supplemental metadata and never establishes trust by itself. Because the current
schema permits either `checksum` or `checksumUrl`, use it only alongside a signer-bound detached
signature. A checksum document may contain one bare SHA-256 digest or common `sha256sum` entries
such as `<digest>  <filename>`. Named entries are accepted only when their safe relative path's
basename exactly matches the artifact URL's final path component.

`signatureUrl` and `allowedSignerFingerprint` must be configured together. The fingerprint is the
40-hex OpenPGP v4 or 64-hex OpenPGP v5 primary/signing-key fingerprint. Fluxion runs GPG with a
machine-readable status channel and requires a valid signature whose signing or primary-key
fingerprint matches this value; a zero GPG exit code by itself is not sufficient. Accepted signature
hash algorithms are SHA-256, SHA-384, and SHA-512. Accepted public-key algorithms are RSA signing,
ECDSA, legacy EdDSA, Ed25519, and Ed448; DSA and weak digest algorithms such as SHA-1 are rejected.

Artifact, checksum, and signature URLs must be absolute HTTPS URLs with a host and no URI user-info.
Redirects that downgrade to HTTP are rejected. Query strings remain available to the in-memory
download request for signed URLs, but query strings and fragments are removed from persisted state.

Supported artifact formats are `.tar.gz`, `.tgz`, `.zip`, `.tar.xz`, and plain binary URLs. Fluxion
extracts `.tar.gz` and `.tgz` locally. `.zip` and `.tar.xz` are delegation-only and require
`binstaller`; Fluxion fails the step if `binstaller` cannot be obtained or cannot represent the
requested install, rather than copying the archive as an executable. Every archive URL requires a
normalized relative POSIX `archivePath`. For local tar archives, Fluxion strips the configured
number of leading components and then requires an exact match with `archivePath`; it never guesses
by basename. `stripComponents` defaults to `0`. Delegation is refused whenever
`stripComponents > 0`, because `binstaller` has no equivalent selector transformation. A missing or
ambiguous regular-file match fails the step.

Built-in downloads are streamed with timeouts. Artifact/signature files are limited to 1 GiB and
checksum documents to 1 MiB; oversized, truncated, interrupted, or unsuccessful downloads are
rejected and partial temporary files are removed. Local tar extraction limits each entry to 1 GiB
and both declared entry sizes and the complete decompressed TAR stream to 2 GiB. The stream limit
includes headers, padding, GNU long-name records, and PAX metadata; partial extraction is removed
on failure. SHA-256 hashing is also streamed rather than loading the artifact into heap memory.
Delegation-only archive downloads and extraction are performed by `binstaller`, so its
independently configured limits apply.

The verified binary is staged beside `installPath`, the configured `mode` and `symlinkPath` are
prepared, and the destination is replaced atomically only after those operations succeed. If
binary commit fails, Fluxion restores the previous symlink entry. Privileged staging is allowed
only in a root-owned directory with no symlink components and no group/other write permission;
other non-writable parents are refused. Delegated installs use an absolute
`$HOME/.apps/<module>/bin/<binary>` canonical target. Success requires that canonical file to
change during the invocation and every declared non-canonical output to be a symlink resolving
exactly to it. Before delegation, Fluxion copies prior regular outputs and records prior symlinks
and absent paths; a nonzero result or rejected output restores that snapshot before the step fails.
Dry-run previews the download URL, archive extraction selection, destination path, mode, and
symlink without downloading or writing files.
When the required trust metadata is absent or incomplete, validation fails and the installer
refuses the download even if validation was bypassed.

---

### `dotbot` — apply dotfiles with dotbot-go

```yaml
- type: dotbot
  name: dotfiles-core
  installerVersion: "v0.4.2"    # default: v0.4.2
  config: "~/.dotfiles/install.conf.yaml"
  dotbotBinary: dotbot          # archive entry to execute; default: dotbot
  probeCommand: "test -f ~/.zshrc && test -f ~/.gitconfig"
```

The executor downloads `dotbot-go` from
`https://github.com/worxbend/dotbot-go/releases/download/<version>/dotbot-linux-amd64.tar.gz`,
extracts the configured binary entry, and runs it with `--config`.

---

### `default-shell` — change the user's login shell

```yaml
- type: default-shell
  name: zsh-default
  shell: /bin/zsh                 # preferred
  probeCommand: "getent passwd $USER | cut -d: -f7 | grep -q zsh"
```

`shellPath` is accepted as a deprecated alias for `shell`.
The shell must be an absolute path to an executable. Live execution uses
`sudo chsh -s <shell> <target-user>` through the same authenticated runner as other privileged
effects.

---

### `oh-my-zsh` — install Oh My Zsh

```yaml
- type: oh-my-zsh
  name: oh-my-zsh
  installDir: "~/.oh-my-zsh"      # optional, default: ~/.oh-my-zsh
  revision: c5ba74cf02cce4c342153f79089100194f30940f
  sha256: 95118b50d062198597e2b73d3a57b609fd95ca68cdc86faf4460d955f0172b61
  probeCommand: "test -d ~/.oh-my-zsh"
```

`revision` must be a full 40-character Git commit, not `master`, a branch, or a mutable tag.
`sha256` verifies that revision's `tools/install.sh` before it runs.

---

### `toolchain` — run an upstream toolchain installer

```yaml
- type: toolchain
  name: rustup
  kind: RUSTUP                    # RUSTUP | JULIAUP | SDKMAN | GENERIC
  installScriptUrl: "https://sh.rustup.rs"
  sha256: 6c30b75a75b28a96fd913a037c8581b580080b6ee9b8169a3c0feb1af7fe8caf
  installArgs:
    - "-y"
    - "--no-modify-path"
  postInstallEnvSource: "~/.cargo/env"
  continueOnError: true
  probeCommand: "test -f ~/.cargo/bin/rustup"
```

`installScript` is accepted as a deprecated alias for `installScriptUrl`.
`sha256` is required. Installer endpoint updates are intentionally fail-closed: review the new
script, update the digest, and rerun instead of executing changed upstream bytes implicitly.

---

### `binstaller-profile` — install portable binaries with binstaller

Delegates binary tool distribution to
[`binstaller`](https://github.com/worxbend/binstaller). Fluxion does not re-declare your tool list:
it points at the `BinaryDistributionProfile` you already maintain and maps its own verbs onto
binstaller's.

| Fluxion | binstaller |
| --- | --- |
| `plan`, `dry-run` | `plan` |
| `apply` | `apply` |
| `status`, `diff` | `versions` |

`dry-run` never invokes `apply`, so a preview cannot touch the machine.

```yaml
- type: binstaller-profile
  name: developer-binaries
  config: "~/.config/binstaller/config.yaml"
  only: [yazi, neovim]        # optional; empty means every tool in the profile
  skip: [zig]                 # optional
  locked: true                # optional; requires lockFile
  lockFile: "~/binstaller.lock.json"
  installerVersion: "v0.2.0"  # binstaller release Fluxion installs if it is not on PATH
  continueOnError: false
```

Tool resolution order is: an installation already on `PATH`, then Fluxion's cache under
`~/.cache/fluxion/tools`, then a fresh download whose SHA-256 is verified against the release's
`.sha256` sidecar. Fluxion never shadows a `binstaller` you manage yourself.

`config` must be a path. An inline profile object is rejected — binstaller owns that schema.

---

### `nerd-fonts` — install Nerd Font families

Delegates to [`nerd-fonts-installer`](https://github.com/worxbend/nerd-fonts-installer). Fluxion
resolves the tool from `PATH`, then from its cache under `~/.cache/fluxion/tools`, then downloads
and checksum-verifies the release asset for the host platform.

```yaml
- type: nerd-fonts
  name: nerd-fonts-install
  installerVersion: "v1.0.7"
  nerdfontBinary: "nerd-fonts-installer"
  config:
    release: "v3.4.0"
    destination: "~/.local/share/fonts/NerdFonts"
    refreshFontCache: true
    families:
      - JetBrainsMono
      - Hack
  probeCommand: "fc-list | grep -qi JetBrains"
```

Instead of declaring `config` inline, point at an installer config you already maintain. This keeps
one source of truth for your font set:

```yaml
- type: nerd-fonts
  name: nerd-fonts-install
  configPath: "~/.config/nerd-fonts-installer/config.yaml"
```

Inline configurations must pin an exact three-component Nerd Fonts release such as `v3.4.0`.
Fluxion rejects mutable selectors such as `latest`. With `configPath`, the external file is used
as-is and remains the profile owner's trust boundary.

The project renamed its binary and release assets at `v1.0.7`. Pinning `v1.0.6` or older still
works — Fluxion tries the current asset name first and the pre-rename name second — but set
`nerdfontBinary: nerdfont-install` as well, since that is what those archives contain.

---

### `shell-reload` — force later work through a fresh shell

```yaml
- type: shell-reload
  name: reload-zsh
  shell: zsh                      # zsh | bash | sh
  description: "Reload shell after installing toolchains"
```

Use this when a previous step writes shell startup files that later commands need to observe.

---

### `shell-command` — run inline shell commands

```yaml
- type: shell-command
  name: system-setup
  shell: /bin/bash              # default: /bin/bash
  commands:                     # required, run in order with "<shell> -lc"
    - "git config --global init.defaultBranch main"
    - "source \"$HOME/.sdkman/bin/sdkman-init.sh\" && sdk install java"
    - "cargo-binstall --no-confirm eza bottom"
  workingDir: /tmp              # optional
  continueOnError: true
  probeCommand: "git config --global --get init.defaultBranch | grep -q main"
```

Use `shell-command` for setup work that is naturally imperative: changing global user
configuration, cloning plugins, installing SDKMAN candidates, running rustup, pnpm, nvm, or similar
upstream installers. Declare repositories and keys with the typed repository and `gpg-key` steps so
their remote artifacts are verified before privileged mutation. Keep package-manager installs in typed `packages`
steps so Fluxion can still isolate and report individual packages.

---

### `assert` — require a host condition before continuing

```yaml
- type: assert
  name: secure-boot-disabled
  command: "mokutil --sb-state | grep -qi disabled"
  message: "Disable Secure Boot before installing this graphics stack."
  shell: /bin/bash              # optional, default: /bin/bash
  workingDir: /tmp              # optional
```

The command runs with `<shell> -lc`. Exit code `0` passes the assertion. Any nonzero exit code
fails the step and stops the job unless the job has `continueOnModuleError: true`.

---

### `manual` — model a human checkpoint

```yaml
- type: manual
  name: github-login
  message: "Run `gh auth login`, then continue."
  probeCommand: "gh auth status" # optional but recommended
```

Manual steps print the configured message in plain CLI output. When `probeCommand` is present,
Fluxion runs it with `/bin/bash -lc`; exit code `0` marks the checkpoint complete and persists it
in state. Without a successful probe, the step fails with the message so the user can complete the
manual work and resume.

---

## Validation rules

- `profile` must not be blank.
- Step `name` values must be unique within a profile.
- Job `name` values must be unique within a profile.
- Job dependencies must reference existing jobs and must not form a cycle.
- At least one job, phase, or module is required.
- `url` for compiled-binary must use `https://`.
- Package names must not contain shell metacharacters.
- `installPath` and `symlinkPath` for compiled-binary must be absolute, normalized paths.
- Archive downloads require an explicit normalized relative POSIX `archivePath`.

---

## Full example

```yaml
profile: fedora-workstation
os:
  type: fedora
  release: "44"

jobs:
  - name: system-foundation
    restartPolicy:
      type: none
    steps:
      - type: packages
        name: core-cli-tools
        packageManager: dnf
        continueOnError: true
        packages:
          - git
          - curl
          - neovim

  - name: development
    dependsOn:
      - system-foundation
    restartPolicy:
      type: none
    steps:
      - type: packages
        name: dev-tools
        packageManager: dnf
        continueOnError: true
        packages:
          - java-21-openjdk-devel
          - golang

      - type: shell-command
        name: git-defaults
        commands:
          - "git config --global init.defaultBranch main"

      - type: manual
        name: github-login
        message: "Run `gh auth login`, then continue."
        probeCommand: "gh auth status"

  - name: desktop-apps
    dependsOn:
      - system-foundation
    restartPolicy:
      type: none
    steps:
      - type: flatpak
        name: desktop-flatpaks
        remote: flathub
        appIds:
          - com.spotify.Client
          - org.telegram.desktop
```

---

### `user-groups` — add a user to supplementary groups

```yaml
- type: user-groups
  name: container-groups
  groups: [docker, libvirt]
  user: bob                 # optional; default is the user running fluxion
  createMissing: false      # optional; groupadd -f before usermod
  logoutCheckpoint: true    # optional; see below
  continueOnError: false
```

`usermod -aG` exits 0 while the *running session* still lacks the group — `docker ps` keeps saying
permission denied until the user logs out. Fluxion detects that by comparing `id -nG` (this
process's own credentials, fixed at login) against `id -nG <user>` (re-read from the group database),
and raises a restart checkpoint when they disagree. Set `logoutCheckpoint: false` for containers and
image builds, where there is no session to log out of.

Membership is append-only. There is no removal syntax, and `-docker` or `!docker` are rejected rather
than ignored: silently dropping a user out of a group they depend on is a worse failure than having
to run `gpasswd -d` by hand.

`createMissing` is off by default. The groups people want here (`docker`, `libvirt`, `kvm`) are
created by their own package, so a missing one usually means a typo or a package that was not
installed — and quietly creating a real but useless group hides that.

When Fluxion itself is run under `sudo`, `SUDO_USER` is used rather than `root`.

---

### `git-config` — set git configuration

```yaml
- type: git-config
  name: git-identity
  scope: global            # global | system | local
  entries:
    user.email: you@example.com
    user.name: your-name
    pull.rebase: "true"
```

Each key is set individually and probed with `git config --get`, so a key that already holds the
desired value is left alone and `fluxion diff` can report drift per key. `system` scope writes
`/etc/gitconfig` and uses sudo.

---

### `git-repo` — clone repositories that are not packaged

```yaml
- type: git-repo
  name: zsh-plugins
  repos:
    - url: https://github.com/tmux-plugins/tpm.git
      dest: ~/.tmux/plugins/tpm
      ref: 0123456789abcdef0123456789abcdef01234567
    - url: https://github.com/zsh-users/zsh-autosuggestions.git
      dest: "${ZSH_CUSTOM:-~/.oh-my-zsh/custom}/plugins/zsh-autosuggestions"
      depth: 1
      ref: 89abcdef0123456789abcdef0123456789abcdef
      submodules: false
```

Destinations support shell-style `${VAR:-default}` and `~`, so paths can be copied straight from an
existing shell script. Every URL must be HTTPS without user-info, query parameters, or a fragment,
and every `ref` must be a full immutable 40-hex commit rather than a branch or tag.

Fluxion initializes a staged repository, fetches the configured commit directly, performs a
detached `FETCH_HEAD` checkout, verifies the exact origin and HEAD, then moves the checkout into
place without overwriting an existing path. The pin remains fetchable even after the upstream
default branch advances beyond a shallow `depth`. Existing destinations are
verified but never pulled or reset; a mismatched origin or HEAD fails closed. The legacy `update`
field is accepted only as `none`. Recursive submodule checkout allows HTTPS transport only.

---

### `systemd-unit` — enable, start, stop, or mask units

```yaml
- type: systemd-unit
  name: services
  scope: system            # system (default) | user
  units:
    - { name: docker, enabled: true, state: started }
    - { name: sshd, enabled: false, state: stopped, mask: true }
```

A bare name is treated as a `.service`. `scope: user` acts on your own manager and uses no sudo.

`systemctl is-enabled` is read by its output word rather than its exit code, because several
non-zero codes mean different things. `static`, `indirect`, `generated` and `alias` units are never
passed to `enable` — that is an error for a unit with no `[Install]` section, and it is already
reachable as a dependency.

When `systemctl` is absent (containers, image builds) the step is skipped rather than failed, so the
same profile stays usable in CI.

---

### `system-setting` — timedatectl / hostnamectl / localectl

```yaml
- type: system-setting
  name: clock
  localRtc: false
  ntp: true
  timezone: Europe/Warsaw
  hostname: workstation
  locale:
    LANG: en_US.UTF-8
```

Every setting is probed with the matching `show --property` first, so only settings that actually
differ are applied and a rerun is a no-op.

---

### `system-update` — full system update

```yaml
- type: system-update
  name: full-update
  packageManager: zypper   # dnf | zypper | pacman | apt
  distUpgrade: true        # zypper dup / apt full-upgrade
  refreshOnly: false       # metadata only
  timeout: PT2H            # ISO-8601; default 2 hours
```

`distUpgrade` is required on rolling releases such as openSUSE Tumbleweed, where a plain `update` is
the wrong verb.

`dnf check-update` exits **100** when updates are available. That is a successful outcome, not a
failure, and is treated as one — otherwise a refresh step would fail on exactly the machines that
had something to install.

---

### `gpg-key` — import repository signing keys

```yaml
- type: gpg-key
  name: vscode-key
  keys:
    - url: https://packages.microsoft.com/keys/microsoft.asc     # rpm --import
      fingerprint: BC528686B50D79E339D3721CEB3E94ADBE1229CF
    - url: https://download.docker.com/linux/debian/gpg          # apt keyring
      keyring: /etc/apt/keyrings/docker.gpg
      fingerprint: 9DC858229FC7DD38854AE2D88D81803C0EBFCD88
```

Omit `keyring` to import into the RPM database; supply it to write a dearmoured key for an
apt `signed-by` source.

Importing a key decides what the machine will trust to install software as root, so URLs must be
`https` or absolute `file` URIs and every key requires its full 40-hex primary `fingerprint`.
Fluxion downloads to a temporary file, requires exactly one primary key with that fingerprint, and
only then installs the keyring or invokes `rpm --import`. Local `file:` sources and existing
keyrings must be regular, non-symlink files no larger than 16 MiB; Fluxion stages a bounded copy
before inspection. Existing keyrings are accepted only when that copy still matches. A mismatch or
unsafe file fails without replacing the existing file or importing the downloaded key.

Each RPM-import key is tracked by its fingerprint; a keyring-backed key is tracked by its absolute
keyring path. URL query parameters and fragments remain available to the download request but are
excluded from plans, events, errors, and persisted identity. `continueOnError: true` attempts later
keys, but a trust failure still fails the module; the phase's separate `continueOnModuleError`
policy then determines whether later modules run.

---

### `tool-packages` — ecosystem package installers

```yaml
- type: tool-packages
  name: rust-tools
  backend: cargo-binstall  # cargo-binstall | cargo | snap | pipx | uv-tool | npm-global | go-install
  packages:
    - eza
    - ripgrep
    - "bottom@0.10.2"      # name@version pins
  continueOnError: true    # default: true
```

Prefer `cargo-binstall` over `cargo`: it fetches prebuilt binaries instead of compiling from source.

Each package installs in its own process, so one yanked crate does not block the other nineteen —
the same isolation `packages` already provides. The backend must be on `PATH`; the step fails with
an actionable message rather than a confusing command-not-found if it is missing. Package names
must be registry identifiers valid for the selected backend; local paths, URLs, direct references,
and option-shaped names or versions are rejected.

---

### `zypper-repository` — add an openSUSE repository

```yaml
- type: zypper-repository
  name: vscode
  id: vscode                                            # default: the step name
  baseUrl: "https://packages.microsoft.com/yumrepos/vscode"
  repoFile: /etc/zypp/repos.d/vscode.repo               # default: /etc/zypp/repos.d/<name>.repo
  gpgKeyUrl: "https://packages.microsoft.com/keys/microsoft.asc"
  enabled: true
  gpgCheck: true
  autoRefresh: true
  checksum:
    algorithm: sha256
    value: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

apt, dnf and pacman each had a repository step kind; zypper repositories could previously only be
declared under `spec.sources`. That asymmetry mattered on openSUSE, where adding a repository next to
the packages that need it is the ordinary case.

Fluxion downloads and verifies a declared key without privileges, parses it without privileges,
then installs the local key and auditable `.repo` file with structured `sudo install` commands.
The generated repository refers to the installed local key, never the remote key URL. Validation
requires HTTPS URLs without user-info, refuses `gpgCheck: true` without `gpgKeyUrl`, and requires a
key URL and checksum to be configured together. `repoFile` must be a direct `.repo` file in
`/etc/zypp/repos.d`; the generated file records `autorefresh=1` or `autorefresh=0` from
`autoRefresh`. An enabled repository cannot disable `gpgCheck`.
