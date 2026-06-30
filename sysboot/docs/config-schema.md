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

---

### `apt-repository` — add an APT source

```yaml
- type: apt-repository
  name: docker
  source: deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian bookworm stable
  sourceList: /etc/apt/sources.list.d/docker.list # default: /etc/apt/sources.list.d/<name>.list
  signingKeyUrl: https://download.docker.com/linux/debian/gpg
  keyring: /etc/apt/keyrings/docker.gpg          # default when signingKeyUrl is set
```

Execution writes the source line with `sudo tee`, optionally installs the signing key with
`curl | sudo gpg --dearmor`, and runs `sudo apt-get update`. `plan --show-commands`, `dry-run`,
`status`, `diff`, and `explain` use the source list path as the item key. Validation fails when the
target OS is not Debian/Ubuntu and warns when no signing key URL is configured.

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
```

Execution writes an auditable `.repo` file with `sudo tee` and refreshes metadata with
`sudo dnf makecache --refresh`. `plan --show-commands`, `dry-run`, `status`, `diff`, and `explain`
use the repo file path as the item key. Validation fails when the target OS is not Fedora and warns
when URLs are not HTTPS or when `gpgCheck` is enabled without a `gpgKeyUrl`.

---

### `pacman-repository` — add an Arch Pacman repository

```yaml
- type: pacman-repository
  name: chaotic-aur
  repository: chaotic-aur                  # default: name
  server: https://cdn-mirror.chaotic.cx/$repo/$arch
  config: /etc/pacman.conf                 # default: /etc/pacman.conf
  sigLevel: Required DatabaseOptional
  include: /etc/pacman.d/chaotic-mirrorlist
  enabled: true                            # default: true
```

Execution appends a repository block with `sudo tee -a` when the repository is not already present
and refreshes package databases with `sudo pacman -Sy`. `plan --show-commands`, `dry-run`,
`status`, `diff`, and `explain` use the repository name as the item key. Validation fails when the
target OS is not Arch and warns when the server is not HTTPS or `sigLevel` is omitted.

---

### `flatpak-remote` — add a Flatpak remote

```yaml
- type: flatpak-remote
  name: flathub                 # required
  remote: flathub               # required
  url: https://flathub.org/repo/flathub.flatpakrepo # required
  system: true                  # default: true; false adds the user remote
```

Execution runs `flatpak remote-add --if-not-exists <remote> <url>`. When `system` is `false`,
Fluxion adds `--user`. `plan --show-commands`, `dry-run`, `status`, `diff`, and `explain` use the
remote name as the item key. Validation warns when the URL is not HTTPS.

---

### `shell-script` — run a shell script

```yaml
- type: shell-script
  name: install-sdkman          # required
  script: scripts/sdkman.sh     # required — relative to config file or absolute
  args:                         # optional, default: []
    - --sdkman
  workingDir: /tmp              # optional, default: config file directory
  continueOnError: false        # default: false
```

The interpreter is detected from the shebang line; falls back to `/bin/bash`. The script is run in a PTY so sudo prompts are handled by the TUI.

---

### `compiled-binary` — download and install a pre-built binary

```yaml
- type: compiled-binary
  name: install-neovim          # required
  binaryName: nvim              # required — display name and extracted file name
  url: https://github.com/...   # required — https only
  checksum:                     # optional but strongly recommended
    algorithm: sha256           # SHA-256 is the only currently supported algorithm
    value: abc123...            # lowercase hex digest
  checksumUrl: https://github.com/.../checksums.txt # optional alternative to checksum
  signatureUrl: https://github.com/.../nvim.tar.gz.asc # optional detached signature
  installPath: /usr/local/bin/nvim  # required — absolute path
  archivePath: nvim-linux64/bin/nvim # optional — selected path inside .tar.gz/.tgz
  stripComponents: 1          # optional — path components stripped before matching archivePath
  mode: "0755"                # optional — POSIX install mode; default: "0755"
  symlinkPath: /usr/local/bin/vim # optional — symlink pointing to installPath
  continueOnError: false        # default: false
```

Use either `checksum` or `checksumUrl`, not both. `checksumUrl` must be HTTPS and may point to a
file containing either a bare SHA-256 digest or common `sha256sum` output such as
`<digest>  <filename>`. `signatureUrl` must be HTTPS and points to a detached signature verified
with `gpg --batch --verify <signature> <downloaded-artifact>`.

Supported artifact formats: `.tar.gz`, `.tgz`, or plain binary URLs. Other archive formats such as
`.zip` and `.tar.xz` are rejected by validation because the installer cannot extract them yet. For
archives, Fluxion copies `archivePath` when provided; otherwise it selects an entry whose stripped
file name matches `binaryName`. `stripComponents` defaults to `0`.

The binary is copied to `installPath`, the configured `mode` is applied, and `symlinkPath` is
created when present. If the parent directory is root-owned, Fluxion uses `sudo cp`, `sudo chmod`,
or `sudo ln -sfn` for the privileged write. Dry-run previews the download URL, archive extraction
selection, destination path, mode, and symlink without downloading or writing files.
When `checksum`, `checksumUrl`, and `signatureUrl` are all omitted, Fluxion logs an explicit warning
and installs from the HTTPS source without integrity verification. Use SHA-256 checksums or detached
signatures for downloaded binaries whenever possible. `fluxion validate --strict` treats missing
compiled-binary integrity metadata as a configuration failure.

---

### `dotbot` — apply dotfiles with dotbot-go

```yaml
- type: dotbot
  name: dotfiles-core
  installerVersion: "v0.2.1"    # default: v0.2.1
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

---

### `oh-my-zsh` — install Oh My Zsh

```yaml
- type: oh-my-zsh
  name: oh-my-zsh
  installDir: "~/.oh-my-zsh"      # optional, default: ~/.oh-my-zsh
  probeCommand: "test -d ~/.oh-my-zsh"
```

---

### `toolchain` — run an upstream toolchain installer

```yaml
- type: toolchain
  name: rustup
  kind: RUSTUP                    # RUSTUP | JULIAUP | SDKMAN | GENERIC
  installScriptUrl: "https://sh.rustup.rs"
  installArgs:
    - "-y"
    - "--no-modify-path"
  postInstallEnvSource: "~/.cargo/env"
  continueOnError: true
  probeCommand: "test -f ~/.cargo/bin/rustup"
```

`installScript` is accepted as a deprecated alias for `installScriptUrl`.

---

### `nerd-fonts` — install Nerd Font families

```yaml
- type: nerd-fonts
  name: nerd-fonts-install
  installerVersion: "v1.0.5"
  nerdfontBinary: "nerdfont-install"
  config:
    release: "latest"
    destination: "~/.local/share/fonts/NerdFonts"
    refreshFontCache: true
    families:
      - JetBrainsMono
      - Hack
  probeCommand: "fc-list | grep -qi JetBrains"
```

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
    - "sudo rpm --import https://packages.microsoft.com/keys/microsoft.asc"
    - "git config --global init.defaultBranch main"
    - "source \"$HOME/.sdkman/bin/sdkman-init.sh\" && sdk install java"
    - "cargo-binstall --no-confirm eza bottom"
  workingDir: /tmp              # optional
  continueOnError: true
  probeCommand: "git config --global --get init.defaultBranch | grep -q main"
```

Use `shell-command` for setup work that is naturally imperative: adding repositories or keys,
changing global user configuration, cloning plugins, installing SDKMAN candidates, running rustup,
pnpm, nvm, or similar upstream installers. Keep package-manager installs in typed `packages`
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
- `installPath` for compiled-binary must be an absolute path.

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
