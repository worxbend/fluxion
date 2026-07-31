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
unbraced `$VAR` remains literal. Interpolation is rejected inside shell expressions
(`commands[].run`, `shellCommand`, `unless`, `probeCommand`, or an assertion command), because
context-free substitution cannot be safe for every shell grammar. Pass data through `env`, or use
structured `command` plus `args`/`argv`.

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
| `requireSudo` | Require a successful sudo preflight before any live mutation. TUI runs may authenticate through the shared sudo session; non-interactive runs require `sudo -n -v` to succeed. Dry runs do not authenticate. |
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
The reserved `files`, `vars`, and `expression` condition fields are rejected until Fluxion has
typed, fail-closed semantics for them.
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
          checksum:
            algorithm: sha256
            value: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
    dnf:
      - name: docker
        kind: rpm-repository
        spec:
          id: docker
          baseUrl: https://download.docker.com/linux/fedora/$releasever/$basearch/stable
          repoFile: /etc/yum.repos.d/docker.repo
          gpgKeyUrl: https://download.docker.com/linux/fedora/gpg
          checksum:
            algorithm: sha256
            value: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
    zypper:
      - name: packman
        kind: zypper-repository
        spec:
          id: packman
          baseUrl: https://ftp.gwdg.de/pub/linux/misc/packman/suse/openSUSE_Tumbleweed/
          repoFile: /etc/zypp/repos.d/packman.repo
          gpgKeyUrl: https://ftp.gwdg.de/pub/linux/misc/packman/suse/openSUSE_Tumbleweed/repodata/repomd.xml.key
          autoRefresh: true
          checksum:
            algorithm: sha256
            value: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
    flatpak:
      - name: flathub
        kind: flatpak-remote
        spec:
          remote: flathub
          url: https://flathub.org/repo/flathub.flatpakrepo
          system: true
          checksum:
            algorithm: sha256
            value: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

Generated source setup sections are `apt`, `dnf`, `rpm`, `zypper`, and `flatpak`.
All source URLs, including the repository URI embedded after optional brackets in an APT `deb` or
`deb-src` line, must use HTTPS and must not contain URI user-info.

APT sources require `spec.source`, an absolute resolved keyring path, and exactly one matching
`signed-by` option. The keyring defaults from the source name when `signingKeyUrl` is present.
Only `arch` and `signed-by` source options are accepted. When `signingKeyUrl` is present, `checksum`
is required and binds the exact response bytes of that signing key. RPM-style DNF/Zypper sources
require `spec.id`
and `spec.baseUrl`; enabled sources must enforce `gpgCheck`, `spec.gpgKeyUrl` is required when
`gpgCheck` is true, and every declared remote key requires a checksum over its exact response bytes.
Flatpak sources require `spec.remote`,
`spec.url`, and a checksum over the exact `.flatpakrepo` response bytes. Sysboot downloads and
verifies these artifacts before any privileged key or repository-file mutation, then passes only
the verified local file across the mutation boundary. Redirects are accepted only when the final
URL remains HTTPS without user-info.

`pacman` source entries are accepted by the DTO but do not currently generate source setup
operations. Their server URL is still transport-validated; a checksum is rejected because the
repository base URL is not a finite artifact that Sysboot can verify.

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

`url` must be HTTPS. `installPath` and `symlinkPath` must be absolute and normalized after `~`
expansion. Archive URLs require an explicit normalized relative POSIX `archivePath`, matched exactly
after `stripComponents` is applied. Supported artifacts are plain binaries, `.tar.gz`, `.tgz`,
`.tar.xz`, and `.zip`; `.tar.xz` and `.zip` require `binstaller`, and delegation refuses nonzero
`stripComponents`. Delegated canonical and declared outputs are snapshotted first and restored when
the external install fails or its resulting paths do not pass Fluxion's verification.

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
      - name: remote
        url: https://example.org/install.sh
        sha256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

Each item must define exactly one of `script` or HTTPS `url`. A remote URL must not contain
user-info and requires `sha256` for the exact response bytes; local scripts must omit `sha256`.
Fluxion verifies a remote script before either normal or `sudo` execution. Common item fields
include `args`, `cwd` or `workingDir`, `env`, `sudo`, `allowedExitCodes`, `creates`, `unless`,
`confirm`, `timeout`, `timeoutSeconds`, and `when`.
Relative local script, working-directory, and `creates` paths are resolved from the directory
containing the profile. When no working directory is declared, the profile directory is used.
Items with `confirm` require `fluxion apply --yes`; plain and TUI execution do not prompt
interactively. Dry-run, plan, and probe-only operations remain available without approval.

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
Relative command working directories and `creates` paths are resolved from the profile directory;
the profile directory is the default working directory.

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
    installerVersion: v1.0.7
    nerdfontBinary: nerd-fonts-installer
    config:
      release: v3.4.0
      destination: ~/.local/share/fonts/NerdFonts
      refreshFontCache: true
      families: [JetBrainsMono, Hack]
```

A textual `config` names an existing installer config to use as-is, instead of generating one:

```yaml
- name: nerd-fonts
  kind: nerd-fonts
  spec:
    config: ~/.config/nerd-fonts-installer/config.yaml
```

Inline `config` must pin an exact three-component release such as `v3.4.0`; mutable selectors such
as `latest` are rejected. `families` must contain at least one font family.

### `dotfiles-apply`

```yaml
- name: dotfiles
  kind: dotfiles-apply
  spec:
    config: ~/.dotfiles/install.conf.yaml
    installerVersion: v0.4.2
    dotbotBinary: dotbot
```

`config` or `configPath` must be a path string. Object-shaped `config` is rejected for this kind.

`fluxion plan` runs `dotbot plan -c <config> --output json`, and `fluxion dry-run` runs
`dotbot -c <config> --dry-run`, so the preview is Dotbot's own per-link plan rather than an opaque
command string. Note that force-linked entries replace matching local config paths when applied.

### `binstaller-profile`

```yaml
- name: developer-binaries
  kind: binstaller-profile
  spec:
    config: ~/.config/binstaller/config.yaml
    only: [yazi, neovim]
    skip: [zig]
    locked: true
    lockFile: ~/binstaller.lock.json
    installerVersion: v0.2.0
```

Delegates to [`binstaller`](https://github.com/worxbend/binstaller): `plan`/`dry-run` map to
`binstaller plan`, `apply` to `binstaller apply`, and drift reporting to `binstaller versions`.
`config` must be a path — an inline `BinaryDistributionProfile` object is rejected, because
binstaller owns that schema. Setting `locked: true` requires `lockFile`.

### `tool-packages`

```yaml
- name: rust-tools
  kind: tool-packages
  spec:
    backend: cargo-binstall
    packages:
      - name: ripgrep
        version: "14.1.0"
      - bat
```

Supported `backend` values are `cargo-binstall`, `cargo`, `snap`, `pipx`, `uv-tool`, `npm-global`,
and `go-install`. Items are either a bare package name or an object with `name` and optional
`version`. `backend` and a non-empty `packages` list are both required.

## System kinds

### `user-groups`

```yaml
- name: developer-groups
  kind: user-groups
  spec:
    user: worxbend          # defaults to the invoking user
    groups: [docker, libvirt]
    createMissing: true
    logoutCheckpoint: true
    message: Log out so the new groups take effect.
```

Group membership is append-only: Fluxion never removes a user from a group, so a leading `-` or `!`
on a group name is rejected rather than silently ignored. `createMissing` creates a group that does
not exist yet. `logoutCheckpoint` records that the change only takes effect after a re-login.

### `git-config`

```yaml
- name: git-identity
  kind: git-config
  spec:
    scope: global           # global | system | local
    entries:
      user.email: dev@example.invalid
      pull.rebase: "false"
```

Every key must be `section.key`; a bare key is rejected because `git config` would reject it too.

### `git-repo`

```yaml
- name: dotfiles-checkout
  kind: git-repo
  spec:
    repos:
      - url: https://github.com/worxbend/dotfiles.git
        dest: ~/.dotfiles
        ref: 0123456789abcdef0123456789abcdef01234567
        depth: 1
        submodules: false
```

`url`, `dest`, and `ref` are required per repo. The URL must be HTTPS without user-info, query
parameters, or a fragment. `ref` must be the full 40-hex commit ID; branch and tag names are
rejected because they can move.

New repositories are initialized in a sibling staging directory, fetch only the configured commit,
check out `FETCH_HEAD` detached, and have both `origin` and `HEAD` verified before being moved into
place. This remains stable when the upstream default branch advances beyond a configured shallow
depth. An existing
destination is inspection-only: its exact origin URL and HEAD must already match, otherwise the
step fails without pulling, resetting, or overwriting it. The legacy `update` field must be `none`
when present. Submodule checkout permits HTTPS remotes only.

### `systemd-unit`

```yaml
- name: container-runtime
  kind: systemd-unit
  spec:
    scope: system           # system | user
    units:
      - name: docker.service
        enabled: true
        state: started      # started | stopped | unchanged
      - name: sshd.service
        mask: true
        enabled: false
```

`name` is required per unit. A unit cannot be both masked and enabled — masking is a refusal to
start, so the pair is a contradiction rather than a precedence question.

### `system-setting`

```yaml
- name: clock-and-locale
  kind: system-setting
  spec:
    localRtc: false
    ntp: true
    timezone: Europe/Warsaw
    hostname: workstation
    locale:
      LANG: en_US.UTF-8
```

At least one setting must be present; an entry that declares none is a configuration mistake, not a
no-op.

### `system-update`

```yaml
- name: refresh-metadata
  kind: system-update
  spec:
    packageManager: dnf
    distUpgrade: false
    refreshOnly: true
    timeout: 30m
```

`packageManager` is required. `distUpgrade` and `refreshOnly` are mutually exclusive: one asks for
the largest possible upgrade and the other asks for no upgrade at all.

### `gpg-key`

```yaml
- name: docker-signing-key
  kind: gpg-key
  spec:
    keys:
      - url: https://download.docker.com/linux/fedora/gpg
        keyring: /etc/pki/rpm-gpg/RPM-GPG-KEY-docker
        fingerprint: 060A61C51B558A7F742B77AAC52FEB6B621E9F35
```

`url` and a full 40-hex primary `fingerprint` are required. URLs must be HTTPS without user-info or
absolute `file:` URIs. Fluxion verifies a staged download before installing it or passing it to
`rpm --import`; an existing keyring is also re-inspected instead of being trusted because it exists.
Local sources and existing keyrings must be regular, non-symlink files no larger than 16 MiB.

RPM imports use the fingerprint as their stable plan and state identity; keyring-backed entries use
the absolute keyring path. Signed-URL query parameters and fragments are used only for the request
and are omitted from output and state. `continueOnError` can attempt the remaining keys, but any key
trust failure still leaves the module failed; phase-level continuation remains a separate policy.
Keyring destinations are normalized and confined to approved system key directories and extensions;
APT keyrings use `.gpg` or `.asc` beneath `/etc/apt/keyrings` or `/usr/share/keyrings`.

### `zypper-repository`

```yaml
- name: packman
  kind: zypper-repository
  spec:
    id: packman
    baseUrl: https://ftp.gwdg.de/pub/linux/misc/packman/suse/openSUSE_Tumbleweed/
    repoFile: /etc/zypp/repos.d/packman.repo
    gpgKeyUrl: https://ftp.gwdg.de/pub/linux/misc/packman/suse/gpg-pubkey.asc
    checksum:
      algorithm: sha256
      value: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
    enabled: true
    gpgCheck: true
    autoRefresh: true
```

`baseUrl` must use HTTPS without user-info, because a repository decides what the machine installs.
`gpgKeyUrl` is required whenever `gpgCheck` is enabled, and any declared key URL requires a
SHA-256 checksum over its exact response bytes. Fluxion verifies and parses the key before the
first privileged mutation, installs a local key, and makes the generated repository refer only to
that local path. `id` defaults to the plan entry name and `repoFile` defaults to
`/etc/zypp/repos.d/<name>.repo`. Enabled repositories cannot disable `gpgCheck`. Repository files
are confined to direct `.repo` children of that directory, and `autoRefresh` is emitted as
`autorefresh=1` or `autorefresh=0`.

## Interrupt and resume

### `interrupt`

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
