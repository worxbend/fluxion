# Tool Integration

Fluxion is the orchestrator, not the installer.

It owns planning, ordering, host-fact selection, conditionals, dry-run, state, resume, reporting, and
the terminal experience. Anything that already has a good dedicated tool is delegated to that tool
over a typed adapter. This page describes how that delegation works and what it guarantees.

## Why delegate

A workstation bootstrapper that reimplements a binary installer ends up with a worse binary
installer. The tools Fluxion delegates to already model plan, dry-run, apply, state, and locking —
often better than a general orchestrator would, because they only have to do one thing.

Delegating also means you keep one source of truth. If you already maintain
`~/.config/binstaller/config.yaml`, Fluxion points at that file rather than asking you to restate its
contents in a second schema.

## How a tool is resolved

Every delegated tool goes through `ToolBroker`, which resolves in a fixed order:

1. **Already on `PATH`.** If you manage the tool yourself, Fluxion uses your installation and does
   not shadow it.
2. **Fluxion's cache**, at `~/.cache/fluxion/tools/<tool>/<version>/<binary>`. Tools are cached per
   version, so a pinned profile does not re-download on every run.
3. **A fresh download** of the release asset for the host platform.

Step 3 is verified. Fluxion downloads the release's checksums — either a `checksums.txt` listing or a
`<asset>.sha256` sidecar, depending on what the project publishes — and refuses to install on a
mismatch. This matches the guarantee each project's own `install.sh` gives; Fluxion does not offer
less.

`fluxion doctor` reports which tools a profile needs and whether they are already available.

### Platform awareness

Asset names are rendered per platform from the tool's template, covering `linux`/`darwin` (or
`macos`, for projects that spell it that way) and `amd64`/`arm64`.

### Asset renames

Projects rename their release assets. `nerd-fonts-installer` did exactly that at `v1.0.7`, renaming
both the archive and the binary inside it. A profile pinning `v1.0.6` must keep working, so each tool
carries an ordered list of candidate asset names: the current name is tried first, earlier names
after. A pin to an older release still resolves.

`KnownToolReleaseIT` checks every candidate asset and checksum URL against the live GitHub releases,
so a future rename fails a test rather than a user's machine. It skips automatically when offline.

## Integrated tools

### binstaller — `binstaller-profile`

[worxbend/binstaller](https://github.com/worxbend/binstaller) installs binary tool distributions
described by a `BinaryDistributionProfile`.

| Fluxion verb | binstaller verb |
| --- | --- |
| `plan`, `dry-run` | `plan` |
| `apply` | `apply` |
| `status`, `diff` | `versions` |

`--only`, `--skip`, `--locked` and `--lock-file` are passed through. A preview never invokes `apply`,
so `fluxion dry-run` cannot touch the machine.

```yaml
- type: binstaller-profile
  name: developer-binaries
  config: "~/.config/binstaller/config.yaml"
  only: [yazi, neovim]
  locked: true
  lockFile: "~/binstaller.lock.json"
```

The schemas are siblings: binstaller's profile uses the same `apiVersion` / `kind` / `metadata` /
`spec.policy` / `spec.plan` grammar and the same `${HOME}` interpolation as Fluxion's
`WorkstationProfile`. Referencing one from the other is a two-line change.

`config` must be a path. An inline profile object is rejected — binstaller owns that schema.

### dotbot — `dotfiles-apply`

[worxbend/dotbot-go](https://github.com/worxbend/dotbot-go) (or `dotbot-scala`, an interchangeable
native backend with the same directives) links dotfiles from a repository.

`fluxion plan` runs `dotbot plan -c <config> --output json`, and `fluxion dry-run` runs
`dotbot -c <config> --dry-run`, so previews show Dotbot's real per-link plan rather than an opaque
command string.

```yaml
- type: dotbot
  name: link-dotfiles
  config: "~/.system-bootstrap/.files/install.conf.yaml"
```

Dotbot link entries commonly set `force: true`, which replaces matching local config paths on apply.
Read the plan output before applying on a machine whose configs you care about.

### nerd-fonts-installer — `nerd-fonts`

[worxbend/nerd-fonts-installer](https://github.com/worxbend/nerd-fonts-installer) installs Nerd Font
families.

Prefer pointing at a config you already maintain:

```yaml
- type: nerd-fonts
  name: fonts
  configPath: "~/.config/nerd-fonts-installer/config.yaml"
  probeCommand: "fc-list | grep -qi JetBrainsMono"
```

An inline `config` block is still supported, and Fluxion generates a temporary installer config from
it. Use `configPath` when the font set should have exactly one definition.

## Pinning versions

Every delegated step accepts `installerVersion`, which pins the release Fluxion installs when the
tool is not already on `PATH`:

```yaml
- type: binstaller-profile
  name: developer-binaries
  config: "~/.config/binstaller/config.yaml"
  installerVersion: "v0.2.0"
```

Defaults come from `dev.sysboot.core.KnownTools`, which is the single place tool versions are
declared. Pinning is recommended for profiles shared across machines: an unpinned `latest` can change
what a "reproducible" bootstrap does.

## Adding a new tool

1. Add a `ToolSpec` to `KnownTools`, with the repository, default version, ordered asset-name
   candidates, OS naming convention, and checksum policy.
2. Add the entry to `ToolSpecTest` so the asset naming is pinned by a test.
3. `KnownToolReleaseIT` picks it up automatically and checks it against the live release.
4. Add a step kind if the tool needs one, or reference it from an existing executor.

The asset template supports `${name}`, `${version}`, `${os}` and `${arch}`.

## What is not delegated

Package managers (`dnf`, `zypper`, `pacman`, `apt`, Flatpak) are driven directly, because there is no
intermediate tool worth inserting and Fluxion needs per-package failure isolation and probing that a
wrapper would hide.

`compiled-binary` predates `binstaller-profile` and still works, including checksum and detached
signature verification. For new profiles, prefer `binstaller-profile`: it handles version resolution,
archive layouts, symlinks, and locking that `compiled-binary` does not.
