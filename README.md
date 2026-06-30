<p align="center">
  <img src="sysboot/docs/assets/banner.svg" alt="Fluxion banner" width="100%">
</p>

<p align="center">
  <a href="https://github.com/worxbend/fluxion/actions/workflows/release.yml"><img alt="Release" src="https://github.com/worxbend/fluxion/actions/workflows/release.yml/badge.svg"></a>
  <a href="https://github.com/worxbend/fluxion/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/worxbend/fluxion/actions/workflows/ci.yml/badge.svg"></a>
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-f89820?logo=openjdk&logoColor=white">
  <img alt="Mill" src="https://img.shields.io/badge/Mill-1.1.6-5b5bd6">
  <img alt="GraalVM" src="https://img.shields.io/badge/GraalVM-native%20ready-f2a900?logo=graalvm&logoColor=111111">
  <img alt="Linux" src="https://img.shields.io/badge/Linux-workstation%20bootstrap-2ea44f?logo=linux&logoColor=white">
</p>

<p align="center">
  <b>Turn a fresh Linux machine into your machine.</b><br>
  One YAML file. One preview. One run. CLI or TUI. No mystery bash soup.
</p>

---

## What Is Fluxion?

`fluxion` is a friendly workstation bootstrapper.

You write a YAML profile that says:

- which config frontend you want: stable `jobs`/`steps` or an ordered `WorkstationProfile` manifest
- which packages you want
- which installer should handle them: `apt`, `dnf`, `pacman`, `zypper`, Flatpak, direct binary downloads, shell installers, Nerd Fonts, dotfiles, or commands
- which jobs depend on other jobs
- where restart or logout checkpoints belong
- what should be skipped when state or live probes show it is already installed

Then Fluxion shows you what it is about to do and runs the matching work for the current machine.

Think of it like:

> "Here is my developer laptop recipe. Please apply only the parts that make sense on this distro."

## Why It Exists

Fresh machines are exciting for about five minutes. Then you remember you need Git, Zsh, Docker,
Flatpak apps, `kubectl`, Rust, fonts, dotfiles, shell setup, and that one command you always forget.

Fluxion makes that boring setup repeatable without turning your dotfiles repo into a giant pile of
shell scripts.

## The Vibe

- Simple YAML instead of custom bash logic everywhere
- Dry-run first so you can inspect the plan before touching the machine
- Distro-aware package steps for Ubuntu, Debian, Fedora, Arch/EndeavourOS, and openSUSE
- Many installer kinds in one profile
- State files for interrupt/resume flows
- Plain CLI output for scripts and CI
- Interactive TUI for picking jobs, steps, and entries
- Native Linux binary via GraalVM release workflow

## Tiny Example

Fluxion supports two config frontends. The stable DAG-oriented schema uses `jobs` and `steps`:

```yaml
profile: my-laptop
os:
  type: fedora
  release: "44"

jobs:
  - name: base-cli
    restartPolicy:
      type: none
    steps:
      - type: packages
        name: core-tools
        packageManager: dnf
        packages:
          - "@development-tools"
          - git
          - curl
          - jq
          - zsh

      - type: compiled-binary
        name: kubectl
        binaryName: kubectl
        url: https://dl.k8s.io/release/v1.30.2/bin/linux/amd64/kubectl
        installPath: /usr/local/bin/kubectl
```

Package managers install one item at a time. If `git` works but `some-wrong-name` fails, Fluxion
still attempts the next package and reports the partial failure clearly.

The newer `WorkstationProfile` manifest frontend is also supported for ordered plans selected by
host facts and `when` rules:

```yaml
apiVersion: initkit.io/v1alpha1
kind: WorkstationProfile
metadata:
  name: fedora-workstation
spec:
  target:
    os:
      distribution: fedora
      release: "44"
  policy:
    continueOnError: true
  plan:
    - name: core-cli
      kind: dnf-packages
      when:
        distribution: fedora
      spec:
        actions:
          - action: check-update
        packages: [git, curl, jq]
```

For manifests, `spec.target.os` is informational metadata. The host detector and per-entry `when`
rules decide which entries run or skip.

## Install

Native Linux builds are published from tags by GitHub Actions.

Release archives are named like:

```text
fluxion-v0.0.1-all.jar
fluxion-v0.0.1-linux-amd64.tar.gz
fluxion-v0.0.1-checksums.sha256
```

When a release archive is unpacked and `fluxion` is on `PATH`:

```bash
fluxion --help
fluxion validate -c ~/.config/fluxion/default.yaml --no-tui
fluxion plan -c ~/.config/fluxion/default.yaml --show-commands --no-tui
```

If you are hacking on the repo, you do not need a global Mill install. The checked-in
`sysboot/mill` launcher is enough.

## Run It

Preview an example without changing your machine:

```bash
cd sysboot
./mill cli.run dry-run -c config/example-fedora.yaml --no-tui
```

Apply it:

```bash
cd sysboot
./mill cli.run apply -c config/example-fedora.yaml
```

Force plain output:

```bash
cd sysboot
./mill cli.run apply -c config/example-fedora.yaml --no-tui
```

Run only selected jobs:

```bash
cd sysboot
./mill cli.run apply -c config/example-fedora.yaml --phase system-foundation --no-tui
```

Resume from a job:

```bash
cd sysboot
./mill cli.run apply -c config/example-fedora.yaml --from-phase development --skip-already-installed
```

Show the saved state path:

```bash
fluxion state path default
```

## Native Binary Workflow

From a release download:

```bash
fluxion plan -c config/example-fedora.yaml --show-commands --no-tui
fluxion dry-run -c config/example-fedora.yaml --no-tui
fluxion apply -c config/example-fedora.yaml
```

From source:

```bash
cd sysboot
./mill cli.run --help
./mill cli.run apply --help
./mill cli.run validate --help
```

Build a native image locally when GraalVM 25 is installed:

```bash
cd sysboot
./mill cli.nativeImage
```

The native binary lands at:

```bash
sysboot/out/cli/nativeImage.dest/native-executable
```

## Build From Source

Requirements:

- JDK 25
- Linux for real package-manager workflows
- GraalVM 25 only if you want native-image locally

Common development commands:

```bash
cd sysboot
./mill __.compile
./mill __.test
./mill cli.run --help
./mill cli.run validate -c config/example-fedora.yaml --no-tui
```

Repository-level validation recipes run from the root checkout and use the checked-in
`sysboot/mill` launcher by default:

```bash
just verify
just validate-configs
just native-smoke
```

Focused assembly and native-image builds run from `sysboot/`:

```bash
cd sysboot
./mill cli.assembly
./mill cli.nativeImage
```

## Docs

- [Command reference](sysboot/docs/commands.md)
- [Config schema](sysboot/docs/config-schema.md)
- [WorkstationProfile manifest reference](sysboot/docs/workstation-profile.md)
- [Developer guide](sysboot/docs/development.md)
- [Testing guide](sysboot/docs/testing.md)
- [Architecture overview](sysboot/docs/architecture.md)
- [Native image notes](sysboot/docs/native-image.md)
- [Release checklist](sysboot/docs/release.md)

Copy-pasteable examples:

- [Fedora](sysboot/config/example-fedora.yaml)
- [Arch / EndeavourOS](sysboot/config/example-arch.yaml)
- [openSUSE](sysboot/config/example-opensuse.yaml)

## Project Status

Fluxion is young, useful, and still moving fast. The stable schema is the `jobs`/`steps` profile
format documented in `sysboot/docs/config-schema.md`. The newer Kubernetes-style
`WorkstationProfile` parser path is implemented and documented in
`sysboot/docs/workstation-profile.md`; shipped distro examples are being expanded separately.

Dry-run and plan modes are non-mutating. Fluxion redacts sudo input, sensitive environment values,
tokens, and password-like text from rendered commands, events, failure text, and TUI state.

Use dry-run. Read the plan. Then let it run.

## Contributing

Small, boring improvements are welcome:

- clearer docs
- more distro examples
- better validation messages
- more installer kinds
- sharper TUI details
- safer execution edges

Start with [sysboot/CONTRIBUTING.md](sysboot/CONTRIBUTING.md).

---

<p align="center">
  <b>Fluxion</b> - because "new laptop day" should feel fun, not like archaeology.
</p>
