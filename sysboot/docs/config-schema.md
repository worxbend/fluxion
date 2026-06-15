# Config Schema Reference

All config files are YAML. Place them in `~/.config/fluxion/` or pass with `-c`.

---

## Top-level fields

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
  installPath: /usr/local/bin/nvim  # required — absolute path
  continueOnError: false        # default: false
```

Supported archive formats: `.tar.gz`, `.tgz`. Plain binaries (no extension) are also supported.

The binary is copied to `installPath`. If the parent directory is root-owned, `sudo cp` is used.
When `checksum` is omitted, Fluxion logs an explicit warning and installs from the HTTPS source
without integrity verification. Use SHA-256 checksums for downloaded binaries whenever possible.

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
