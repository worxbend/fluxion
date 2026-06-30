# fluxion

Linux bootstrapper for people who would rather write one YAML file than babysit a fresh machine for three hours.

`fluxion` reads a profile, installs your packages, Flatpaks, scripts, dotfiles, shell bits, and prebuilt binaries, then keeps moving when one item acts up. TUI when you want the pretty run screen. `--no-tui` when you just want logs and zero drama.

## What It Does

- Boots Fedora, Arch, openSUSE, Debian, and Ubuntu setups from YAML.
- Supports the stable `jobs`/`steps` schema and the ordered `WorkstationProfile` manifest frontend.
- Talks to `dnf`, `pacman`, `paru`, `yay`, `apt`, `zypper`, and Flatpak.
- Runs jobs in dependency order with restart checkpoints.
- Installs packages one by one, so one bad package does not trash the whole run.
- Tracks state under `~/.local/share/fluxion`.
- Builds as a fat JAR or a native Linux binary with GraalVM 25.

## Grab It

Release assets are named like this:

```bash
fluxion-v0.0.1-all.jar
fluxion-v0.0.1-linux-amd64.tar.gz
fluxion-v0.0.1-checksums.sha256
```

Or build it yourself:

```bash
cd sysboot
./mill cli.assembly
./mill cli.nativeImage
```

The native binary lands at:

```bash
./out/cli/nativeImage.dest/native-executable
```

## Run It

```bash
fluxion validate -c ~/.config/fluxion/fedora.yaml
fluxion generate --os auto --profile starter --preset developer --output ~/.config/fluxion/starter.yaml
fluxion list -c ~/.config/fluxion/fedora.yaml
fluxion plan -c ~/.config/fluxion/fedora.yaml --show-commands --no-tui
fluxion dry-run -c ~/.config/fluxion/fedora.yaml
fluxion apply -c ~/.config/fluxion/fedora.yaml
```

No TUI:

```bash
fluxion apply -c config/example-fedora.yaml --no-tui
```

Fluxion starts the terminal UI only when a real console is available. CI, pipes, and build-tool runs
fall back to plain output automatically; `--no-tui` remains the clearest choice for scripts.
In TUI mode, the pre-run selector lets you toggle whole jobs, drill into steps, and drill into
step entries before starting the selected subset.

From the repo before installing:

```bash
cd sysboot
./mill cli.run validate -c config/example-fedora.yaml
./mill cli.run plan -c config/example-fedora.yaml --show-commands --no-tui
./out/cli/nativeImage.dest/native-executable apply -c config/example-fedora.yaml --no-tui
```

## Commands

```text
fluxion [GLOBAL OPTIONS] <COMMAND>

Global:
  -c, --config=FILE    default: ~/.config/fluxion/default.yaml
  --no-tui             plain stdout mode
  -v, --verbose        more noise
  -h, --help           help
  --version            version

Commands:
  apply      apply the profile
  run        alias for apply
  dry-run    show what would happen
  validate   check the YAML
  lint       score profile quality and safety
  list       print modules
  plan       show job order
  graph      render the job dependency graph
  diff       show host changes needed
  explain    explain why a phase or item runs
  status     show last run status, or `--resume-command`
  state      show/reset/forget saved state
  generate   create a starter YAML profile
  snapshot   write a host inventory JSON
  import     generate profile fragments from this host
  doctor     check host readiness for a profile
```

State moves:

```bash
fluxion state show default
fluxion state path default
fluxion state forget --profile default --item git
fluxion state reset default --force
```

## Config Shape

Put profiles in `~/.config/fluxion/` or pass `-c`. Fluxion accepts both the stable
`jobs`/`steps` schema and the newer `WorkstationProfile` manifest schema.

```yaml
profile: fedora-workstation
os:
  type: fedora
  release: "44"

jobs:
  - name: base
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

      - type: flatpak
        name: apps
        remote: flathub
        appIds:
          - com.spotify.Client

      - type: shell-command
        name: git-defaults
        commands:
          - "git config --global init.defaultBranch main"

  - name: dev
    dependsOn: [base]
    steps:
      - type: compiled-binary
        name: lazygit
        binaryName: lazygit
        url: https://github.com/jesseduffield/lazygit/releases/download/v0.61.0/lazygit_0.61.0_Linux_x86_64.tar.gz
        installPath: /usr/local/bin/lazygit
```

The same CLI commands also load `WorkstationProfile` manifests:

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
  vars:
    binDir: ${HOME}/.local/bin
  sources:
    flatpak:
      - name: flathub
        kind: flatpak-remote
        spec:
          remote: flathub
          url: https://flathub.org/repo/flathub.flatpakrepo
          system: true
  plan:
    - name: base-cli
      kind: dnf-packages
      when:
        distribution: fedora
      spec:
        actions:
          - action: check-update
        packages: [git, curl, neovim]

    - name: apps
      kind: flatpak-packages
      spec:
        apps: [com.spotify.Client]
```

For manifests, `spec.target.os` is informational. Host facts and per-entry `when` rules decide
which plan entries run or skip.

Supported stable step types:

- `packages`
- `flatpak`
- `flatpak-remote`
- `apt-repository`
- `rpm-repository`
- `pacman-repository`
- `shell-script`
- `shell-command`
- `compiled-binary`
- `dotbot`
- `oh-my-zsh`
- `nerd-fonts`
- `toolchain`
- `default-shell`
- `shell-reload`
- `assert`
- `manual`

Supported WorkstationProfile plan kinds include `apt-packages`, `dnf-packages`, `pacman-packages`,
`zypper-packages`, `aur-packages`, `cargo-packages`, `sdkman-packages`, `flatpak-packages`,
`binary-downloads`, `shell-scripts`, `commands`, `nerd-fonts`, `dotfiles-apply`, `file-writes`,
and `interrupt`.

Full schema docs live in `docs/config-schema.md` and `docs/workstation-profile.md`.

More docs:

- `docs/commands.md`
- `docs/architecture.md`
- `docs/enhancements.md`
- `docs/workstation-profile.md`

## Dev Loop

From the repository root:

```bash
just verify
just validate-configs
just native-smoke
```

From `sysboot/`, use the checked-in Mill wrapper:

```bash
cd sysboot
./mill __.test
./mill cli.test
./mill executor.test.testOnly dev.sysboot.executor.DnfPackageInstallerTest
./mill cli.assembly
./mill cli.nativeImage
```

Repo shape:

```text
cli -> app -> tui -> executor -> config-parser -> core
```

No Spring. No Gradle. No service locator soup. Constructors and Mill.

## Exit Codes

```text
0  all good
1  app blew up
2  bad CLI input
3  bad config
4  filesystem/IO problem
5  package manager or external command failed
75 execution paused at an interrupt or restart checkpoint
```

## Notes

- Java 25.
- Mill 1.1.6 through `./mill`.
- Native image uses GraalVM Community 25.
- Dry-run, plan, status, diff, doctor, and validate are non-mutating.
- Passwords, sudo input, sensitive environment values, tokens, and password-like text should never hit logs, events, failure text, or TUI state.
- If TamboUI snapshots are not available, use `--no-tui`.

That is the tool. One file says what the machine should become. `fluxion` does the boring part.
