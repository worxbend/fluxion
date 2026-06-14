# sysboot

A single-binary, config-driven, idempotent system bootstrap tool for Linux development environments. Declare your packages, Flatpaks, scripts, and binaries in a YAML file; `sysboot` installs them with a live TUI and per-item error isolation.

Supports **Fedora** (DNF), **Arch** (pacman/paru/yay), **openSUSE** (zypper), and **Debian/Ubuntu** (APT).

---

## Prerequisites

| Tool | Version |
|---|---|
| GraalVM | 24+ (`native-image` on PATH) |
| Java | 24+ |
| Mill | 0.12.3+ |

Install GraalVM and `native-image`:

```bash
sdk install java 24-graal          # via SDKMAN
gu install native-image
```

---

## Quick Start

```bash
# 1. Clone and enter the project
git clone https://github.com/you/sysboot && cd sysboot

# 2. Build the native binary (~2-4 minutes)
mill cli.nativeImage

# 3. Run your first profile
./out/cli/nativeImage.dest/sysboot run -c config/example-fedora.yaml
```

Run in headless mode (no TUI):

```bash
./sysboot run -c config/example-fedora.yaml --no-tui
```

---

## CLI Reference

```
sysboot [GLOBAL OPTIONS] <COMMAND> [OPTIONS]

Global Options:
  -c, --config=FILE    Config file [default: ~/.config/sysboot/default.yaml]
  --no-tui             Disable TUI, use plain stdout
  -v, --verbose        Verbose logging
  -h, --help           Show help
  --version            Show version

Commands:
  run          Execute a bootstrap profile
  dry-run      Show what would be executed without any changes
  validate     Validate a config file
  list         List all modules in a config
  plan         Show the phase execution plan
  status       Show the last execution status
  state        Show, reset, or forget persisted state
```

### `run`

```bash
sysboot run -c fedora.yaml
sysboot run -c fedora.yaml --modules core-cli-tools,dev-tools
sysboot run -c fedora.yaml --dry-run
sysboot run -c fedora.yaml --no-tui
```

### `dry-run`

```bash
sysboot dry-run -c fedora.yaml
```

### `validate`

```bash
sysboot validate -c fedora.yaml
# ✓ Config is valid: profile 'fedora-workstation' with 7 module(s)
```

### `list`

```bash
sysboot list -c fedora.yaml
```

---

## Config File Format

Place configs in `~/.config/sysboot/` or pass with `-c`.

```yaml
profile: <string>           # unique identifier for this profile
os:
  type: fedora | arch | opensuse | debian
  release: <string>         # e.g. "41" for Fedora, optional for Arch

jobs:
  - name: <string>
    dependsOn: []           # optional
    continueOnModuleError: true
    restartPolicy:
      type: none | prompt-logout | requires-new-shell
    steps:
      # --- Package module ---
      - type: packages
        name: <string>
        packageManager: dnf | pacman | paru | yay | apt | zypper
        continueOnError: true   # default: true — don't abort on one package failure
        packages:
          - <package-name>

      # --- Flatpak module ---
      - type: flatpak
        name: <string>
        remote: flathub         # default: flathub
        appIds:
          - <app.id>

      # --- Shell script module ---
      - type: shell-script
        name: <string>
        script: <path>          # relative to config file or absolute
        args:
          - <arg>
        workingDir: <path>      # optional, defaults to config file directory
        continueOnError: false  # default: false

      # --- Compiled binary module ---
      - type: compiled-binary
        name: <string>
        binaryName: <string>    # display name
        url: https://...        # https only
        checksum:               # optional, logs a warning when omitted
          algorithm: sha256
          value: <hex>
        installPath: <absolute-path>
        continueOnError: false
```

Top-level `modules` and `phases` are still accepted for older configs, but `jobs` with `steps` is
the preferred workflow-style schema. See
`docs/config-schema.md` for the full annotated schema and restart policy behavior.

---

## Adding a New Profile

1. Create `~/.config/sysboot/my-profile.yaml` following the schema above.
2. Run `sysboot validate -c my-profile.yaml` to check it.
3. Run `sysboot list -c my-profile.yaml` to preview modules.
4. Run `sysboot dry-run -c my-profile.yaml` to preview commands.
5. Run `sysboot run -c my-profile.yaml` when ready.

---

## Adding a New Package Manager

`sysboot` is open to extension via the `PackageManagerExecutor` interface in the `core` module:

```java
// 1. Add enum value to PackageManagerKind
public enum PackageManagerKind { DNF, PACMAN, ..., MY_PM }

// 2. Implement PackageManagerExecutor in the executor module
public final class MyPmInstaller extends AbstractPackageInstaller {
    @Override public boolean supports(PackageManagerKind kind) { return kind == PackageManagerKind.MY_PM; }
    @Override protected List<String> buildInstallCommand(PackageName pkg) {
        return List.of("mypm", "install", pkg.value());
    }
}

// 3. Register in ApplicationContext.buildRegistry(...)
new PackageManagerExecutorRegistry(List.of(..., new MyPmInstaller(runner, sudo)));
```

No other code changes required.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  cli  (picocli entry-point, Main, SysbootCommand)           │
├─────────────────────────────────────────────────────────────┤
│  app  (ApplicationContext — compile-time DI wiring)         │
├─────────────────────────────────────────────────────────────┤
│  tui  (TamboUI screens, event listener, sudo provider)      │
├─────────────────────────────────────────────────────────────┤
│  executor  (package managers, shell runner, orchestrator)   │
├─────────────────────────────────────────────────────────────┤
│  config-parser  (YAML → domain model via Jackson)           │
├─────────────────────────────────────────────────────────────┤
│  core  (domain model, value objects, ports — zero deps)     │
└─────────────────────────────────────────────────────────────┘

Dependency direction:  cli → app → tui → executor → config-parser → core
```

Each layer depends only on layers below it. The `core` module has zero production dependencies.

---

## TUI Note

The TUI requires `dev.tamboui:tamboui-tui:0.3.0-SNAPSHOT` from the Sonatype snapshots repository. If TamboUI is unavailable, build with `--no-tui` and use plain stdout mode. The `--no-tui` flag has no dependency on TamboUI.
