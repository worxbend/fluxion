# WorkstationProfile Manifest Reference

WorkstationProfile is Fluxion's manifest frontend for ordered workstation bootstrap plans. It sits
beside the stable `jobs`/`steps` schema documented in [config-schema.md](config-schema.md):

- Use `jobs`/`steps` when you want the stable DAG-oriented schema with `dependsOn`.
- Use `WorkstationProfile` when you want one ordered manifest plan selected by host facts and
  per-entry `when` rules.

The accepted `apiVersion` is currently `initkit.io/v1alpha1` as a compatibility identifier.
Fluxion remains the product and command name.

```yaml
apiVersion: initkit.io/v1alpha1
kind: WorkstationProfile
metadata:
  name: developer-workstation
spec:
  target:
    os:
      distribution: fedora
      release: "44"
  policy:
    dryRun: false
    continueOnError: true
  vars:
    binDir: ${HOME}/.local/bin
  plan:
    - name: core-cli
      kind: dnf-packages
      when:
        distribution: fedora
      spec:
        packages: [git, curl, ripgrep]
```

## Top-level fields

| Field | Required | Description |
|---|---:|---|
| `apiVersion` | yes | Must be `initkit.io/v1alpha1`. |
| `kind` | yes | Must be `WorkstationProfile`. |
| `metadata.name` | yes | Fluxion profile identity used for state, reporting, and resume checks. |
| `metadata.labels` | no | Optional labels retained by the DTO surface. |
| `spec.target` | yes | Manifest target metadata. |
| `spec.policy` | no | Manifest-level execution defaults. |
| `spec.vars` | no | String variables available to `${...}` interpolation. |
| `spec.sources` | no | Repository and Flatpak remote declarations used by selected package entries. |
| `spec.plan` | no | Ordered plan entries. At least one selected entry is normally expected for useful runs. |

## Target semantics

`spec.target.os` is informational for WorkstationProfile manifests. Fluxion maps it into the core
target model so validation, state, and reports have a declared target, but it does not select work.

Plan selection is driven by:

- Host facts detected at runtime: OS family, distribution, version, codename, and architecture.
- `when` rules on each `spec.plan[]` entry.
- Item-level `when` rules inside structured `commands`, `shell-scripts`, and `file-writes` items.

Supported target distributions are `fedora`, `arch`, `opensuse`, `debian`, and `ubuntu`.
`release`, `version`, and `codename` are optional metadata fields; Debian and Ubuntu prefer
`codename`, then `release`, then `version`.

## Variables

Fluxion interpolates `${...}` tokens across manifest string fields before validation and mapping.

Variable lookup order:

1. Runtime environment variables, plus default `HOME` and `USER`.
2. `spec.vars` values.
3. Host variables: `host.os.name`, `host.os.arch`, `host.user`, and `host.home`.

`spec.vars` values may reference other variables. Cycles and unresolved variables are validation
errors with field paths; unresolved plan-entry variables include the plan entry name in the error.
Only braced `${name}` syntax is interpreted. Shell syntax such as `$(...)`, backticks, globs, and
unbraced `$VAR` remains literal.

## Policy

```yaml
spec:
  policy:
    dryRun: true
    continueOnError: false
    requireSudo: true
    statePath: ~/.local/share/fluxion/state/developer-workstation.json
```

| Field | Description |
|---|---|
| `dryRun` | Config-level dry-run default. CLI dry-run modes still force non-mutating execution. |
| `continueOnError` | Default for plan entries that do not set `execution.continueOnError`. |
| `requireSudo` | Default policy value for generated operations that honor sudo requirements. |
| `statePath` | Optional compatibility field validated for path safety. Runtime state currently uses Fluxion's profile state directory. |

Per-entry `execution.continueOnError` overrides the manifest default:

```yaml
execution:
  continueOnError: false
  requireSudo: true
  parallelism: 1
  timeoutSeconds: 600
  shell: /bin/bash
  workingDir: /tmp
  env:
    FEATURE_FLAG: enabled
```

The current execution engine runs manifest entries in order. Fields beyond
`execution.continueOnError` are accepted by the DTO surface for compatibility, but only implemented
where the mapped module kind supports the corresponding behavior.

## Conditions

`when` selects or skips plan entries using host facts and PATH checks.

```yaml
when:
  distribution:
    oneOf: [debian, ubuntu]
  architecture: amd64
  commandExists: apt
```

Supported conditions:

| Field | Meaning |
|---|---|
| `os`, `osFamily` | Match host OS family. |
| `distribution`, `distributions` | Match host distribution. |
| `version` | Match host version. |
| `codename` | Match host codename. |
| `architecture`, `architectures` | Match host architecture. |
| `commands` | All listed commands must exist on `PATH`. |
| `commandExists` | At least one listed command must exist on `PATH`. |
| `oneOf` | Select when any nested `when` branch matches. |

Matchers may be a string, a list of strings, or an object with `oneOf`, `equals`, or `value`.
Skipped entries are reported in `plan`, `dry-run`, `apply --no-tui`, and the TUI with their skip
reason.

## Source setup

`spec.sources` declares package repositories and Flatpak remotes. Fluxion runs generated source
setup as a prelude before selected package entries that need that package manager. Sources in
generated sections for unused package managers are reported as skipped manifest work instead of
being applied.

```yaml
spec:
  sources:
    apt:
      - name: docker
        kind: apt-repository
        spec:
          source: deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu noble stable
          sourceList: /etc/apt/sources.list.d/docker.list
          signingKeyUrl: https://download.docker.com/linux/ubuntu/gpg
          keyring: /etc/apt/keyrings/docker.gpg
    dnf:
      - name: docker
        kind: rpm-repository
        spec:
          id: docker
          baseUrl: https://download.docker.com/linux/fedora/$releasever/$basearch/stable
          repoFile: /etc/yum.repos.d/docker.repo
          gpgKeyUrl: https://download.docker.com/linux/fedora/gpg
    zypper:
      - name: packman
        kind: zypper-repository
        spec:
          id: packman
          baseUrl: https://ftp.gwdg.de/pub/linux/misc/packman/suse/openSUSE_Tumbleweed/
          repoFile: /etc/zypp/repos.d/packman.repo
          gpgKeyUrl: https://ftp.gwdg.de/pub/linux/misc/packman/suse/openSUSE_Tumbleweed/repodata/repomd.xml.key
    flatpak:
      - name: flathub
        kind: flatpak-remote
        spec:
          remote: flathub
          url: https://flathub.org/repo/flathub.flatpakrepo
          system: true
```

Generated source setup sections are `apt`, `dnf`, `rpm`, `zypper`, and `flatpak`.
APT sources require `spec.source`. RPM-style DNF/Zypper sources require `spec.id` and
`spec.baseUrl`; `spec.gpgKeyUrl` is required when `gpgCheck` is true. Flatpak sources require
`spec.remote` and `spec.url`. Optional source checksums use SHA-256. `pacman` source entries are
accepted by the DTO and checksum validation surface, but they do not currently generate source
setup operations.

## Package kinds

Package plan entries install each package item in a separate process so one failed item does not
prevent later items in the same entry from being attempted.

| Kind | Package manager | Required spec |
|---|---|---|
| `apt-packages` | `apt` | `packages` list |
| `dnf-packages` | `dnf` | `packages` list |
| `pacman-packages` | `pacman` | `packages` list |
| `zypper-packages` | `zypper` | `packages` list |
| `aur-packages` | `paru` or `yay` | `packageManager: paru|yay`, `packages` list |
| `cargo-packages` | `cargo` | `packages` list |
| `sdkman-packages` | SDKMAN shell command | `packages` as strings or `{candidate, version}` objects |
| `flatpak-packages` | `flatpak` | `apps` or `appIds` list |

System package kinds may define pre-install actions:

```yaml
spec:
  actions:
    - action: update
    - action: upgrade
```

Supported actions are:

| Kind | Actions |
|---|---|
| `apt-packages` | `update`, `upgrade`, `dist-upgrade` |
| `dnf-packages` | `check-update`, `upgrade`, `swap`, `groupupdate`, `group-update` |
| `pacman-packages` | `sync-upgrade`, `syu`, `upgrade` |
| `zypper-packages` | `refresh`, `update`, `dup`, `dup-from` |

## Installer kinds

### `binary-downloads`

```yaml
- name: ripgrep
  kind: binary-downloads
  spec:
    binaryName: rg
    url: https://example.invalid/ripgrep.tar.gz
    installPath: ~/.local/bin/rg
    archivePath: ripgrep/rg
    stripComponents: 1
    mode: "0755"
    symlinkPath: ~/.local/bin/ripgrep
    checksum:
      algorithm: sha256
      value: 0000000000000000000000000000000000000000000000000000000000000000
```

`url` must be HTTPS. `installPath` and `symlinkPath` must be absolute after `~` expansion.
Supported artifacts are plain binaries, `.tar.gz`, and `.tgz`.

### `shell-scripts`

```yaml
- name: setup-script
  kind: shell-scripts
  spec:
    scripts:
      - name: local
        script: ./scripts/setup.sh
        args: [--quiet]
        cwd: /tmp
        sudo: false
        allowedExitCodes: [0]
        timeout: 10m
```

Each item must define exactly one of `script` or HTTPS `url`. Common item fields include `args`,
`cwd` or `workingDir`, `env`, `sudo`, `allowedExitCodes`, `creates`, `unless`, `confirm`, `timeout`,
`timeoutSeconds`, and `when`.

### `commands`

```yaml
- name: git-defaults
  kind: commands
  spec:
    commands:
      - "git config --global init.defaultBranch main"
      - name: direct-command
        argv: [git, config, --global, pull.rebase, "false"]
```

String commands run through the configured shell with `-lc`. Array commands and object commands
with `argv`, array `run`, or `command` plus `args` stay direct argv commands. Object commands with
string `run` or `shellCommand` are shell commands.

### `file-writes`

```yaml
- name: write-tool-config
  kind: file-writes
  spec:
    files:
      - name: tool-config
        destination: /etc/tool/tool.conf
        content: |
          enabled=true
        owner: root
        group: root
        mode: "0644"
        sudo: true
      - name: local-copy
        destination: ~/.config/tool/local.conf
        source: /home/me/dotfiles/tool/local.conf
```

Each item requires an absolute `destination` and exactly one of string `content` or absolute local
`source`. Optional fields are `owner`, `group`, `mode`, `sudo`, and item-level `when`.

### `nerd-fonts`

```yaml
- name: nerd-fonts
  kind: nerd-fonts
  spec:
    installerVersion: v1.0.5
    nerdfontBinary: nerdfont-install
    config:
      release: latest
      destination: ~/.local/share/fonts/NerdFonts
      refreshFontCache: true
      families: [JetBrainsMono, Hack]
```

`config` must be an object for `nerd-fonts`. `families` must contain at least one font family.

### `dotfiles-apply`

```yaml
- name: dotfiles
  kind: dotfiles-apply
  spec:
    config: ~/.dotfiles/install.conf.yaml
    installerVersion: v0.2.1
    dotbotBinary: dotbot
```

`config` or `configPath` must be a path string. Object-shaped `config` is rejected for this kind.

## Interrupt and resume

Use `kind: interrupt` to write a resumable checkpoint and stop cleanly.

```yaml
- name: relogin
  kind: interrupt
  spec:
    message: Log out and back in before continuing.
    instructions:
      - Reopen a terminal.
      - Run the resume command printed by Fluxion.
    resumeFrom: next
    exitCode: 75
```

`resumeFrom` is `next` by default and records the interrupt entry as complete. `current` resumes at
the interrupt entry. `exitCode` defaults to `75` and must be between `0` and `255`.

Fluxion records manifest identity and fingerprint metadata in state. A later apply rejects stale
state when the manifest name or fingerprint no longer matches; use `fluxion apply --reset-state`
or `fluxion state reset <profile> --force` when you intentionally want to discard state.

## Dry-run and safety guarantees

`fluxion plan --show-commands`, `fluxion dry-run`, and `fluxion apply --dry-run` render selected,
skipped, and source setup work without mutating the system.

Safety guarantees:

- Dry-run does not install packages, write files, download binaries, add remotes, add repositories,
  save interrupt state, or run shell commands.
- Package, Flatpak, Cargo, and SDKMAN items are attempted independently inside an entry.
- Source setup runs before package installs and fails before dependent packages unless policy allows
  continuation.
- Sensitive environment values, sudo input, bearer tokens, URL credentials, and password-like text
  are redacted from rendered commands, events, failure text, and TUI state.
- Shell strings and direct argv commands stay distinct so previews and redaction reflect the real
  execution boundary.
- File writes, downloads, and privileged operations are represented by typed modules rather than
  hidden imperative setup when a WorkstationProfile kind exists for them.
