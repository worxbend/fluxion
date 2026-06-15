# Fluxion

Fluxion is a YAML-driven Linux workstation bootstrapper. It installs packages, Flatpaks,
scripts, dotfiles, shell tooling, Nerd Fonts, and prebuilt binaries from one declarative profile,
then records state so reruns can skip work safely.

The active codebase lives in `sysboot/` and uses Java 25 with Mill 1.1.6.

## What It Does

- Boots Fedora, Arch, openSUSE, Debian, and Ubuntu-style workstations from YAML.
- Supports `dnf`, `pacman`, `paru`, `yay`, `apt`, `zypper`, and Flatpak.
- Runs phase/job DAGs with dependency ordering and restart checkpoints.
- Installs packages one by one so one failed package does not block unrelated work.
- Supports plain CLI output and a TUI path.
- Builds as a fat JAR or GraalVM native Linux binary.

## Build

```bash
cd sysboot
./mill __.test
./mill cli.assembly
./mill cli.nativeImage
```

The assembled JAR is:

```bash
sysboot/out/cli/assembly.dest/out.jar
```

The native binary is:

```bash
sysboot/out/cli/nativeImage.dest/native-executable
```

## Run

From the repo:

```bash
cd sysboot
java -jar out/cli/assembly.dest/out.jar validate -c config/example-fedora.yaml --no-tui
java -jar out/cli/assembly.dest/out.jar plan -c config/example-fedora.yaml --no-tui
java -jar out/cli/assembly.dest/out.jar generate --os fedora --profile starter --output /tmp/starter.yaml
java -jar out/cli/assembly.dest/out.jar run -c config/example-fedora.yaml --no-tui
```

Native:

```bash
cd sysboot
./out/cli/nativeImage.dest/native-executable --help
./out/cli/nativeImage.dest/native-executable validate -c config/example-fedora.yaml --no-tui
```

## Commands

```text
run        execute a profile
dry-run    show what would be executed
validate   validate YAML
list       list configured modules
plan       show phase order and planned work
status     show current status
state      show/reset/forget/path persisted state
generate   create a starter YAML profile
```

## Config

Profiles live in YAML. Example:

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
        name: core-cli
        packageManager: dnf
        packages:
          - git
          - curl
          - neovim

  - name: desktop-apps
    dependsOn: [system-foundation]
    steps:
      - type: flatpak
        name: desktop-flatpaks
        remote: flathub
        appIds:
          - com.spotify.Client
```

Full schema: `sysboot/docs/config-schema.md`.

Example profiles: `sysboot/config/`.

## Development Notes

Module direction is:

```text
cli -> app -> tui -> executor -> config-parser -> core
```

Keep build/test commands rooted in `sysboot/`. Repository planning and follow-up work lives in
the root `TODO.md`.
