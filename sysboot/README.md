# sysboot

A single-binary, config-driven, idempotent system bootstrap tool for Linux development environments. Declare your packages, Flatpaks, scripts, and binaries in a YAML file; `sysboot` installs them with a live TUI and per-item error isolation.

Supports **Fedora** (DNF), **Arch** (pacman/paru/yay), **openSUSE** (zypper), and **Debian/Ubuntu** (APT).

---

## Prerequisites

| Tool | Version |
|---|---|
| GraalVM | 25+ (`native-image` on PATH) |
| Java | 25+ |
| Mill | Included `./mill` bootstrap script |

Install GraalVM and `native-image`:

```bash
sdk install java 25.0.2-graalce    # via SDKMAN
```

---

## Quick Start

```bash
# 1. Enter the active project root
cd sysboot

# 2. Validate the codebase
./mill __.test

# 3. Build the native binary
./mill cli.nativeImage

# 4. Run your first profile
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

### Exit Codes

| Code | Meaning |
|---:|---|
| 0 | Success |
| 1 | Unexpected application failure |
| 2 | Invalid command-line usage or user input |
| 3 | Configuration load, parse, or validation error |
| 4 | Local file-system or stream I/O error |
| 5 | External command, package manager, or runtime dependency error |

Expected user-facing failures are printed to `stderr` without Java stack traces.

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

### `state`

```bash
sysboot state show default
sysboot state path default
sysboot state forget --profile default --item git
sysboot state reset default --force
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

More detail is available in `docs/architecture.md`.

---

## Build, Test, And Quality

```bash
# Compile all modules
./mill __.compile

# Run all tests
./mill __.test

# Run focused tests
./mill cli.test
./mill executor.test.testOnly dev.sysboot.executor.DnfPackageInstallerTest

# Build a runnable fat JAR
./mill cli.assembly

# Optional formatter/lint recipes from repository root
just format
just lint
just verify
```

The current lint gate is Java compilation with `-Xlint:all`. Formatting uses the pinned
`google-java-format` recipe in the repository `justfile`.

---

## Native Linux Binary

```bash
cd sysboot
./mill cli.nativeImage
./out/cli/nativeImage.dest/sysboot --version
```

The native build targets GraalVM CE 25.0.2 or newer. It is Linux-first and dynamically linked against the host C library by default
(typically glibc on mainstream distributions). It uses `--no-fallback`, includes Jackson DTO and
Picocli reflection metadata from `graal/`, and enables HTTP(S) URL protocols for compiled-binary
downloads. See `docs/native-image.md` for the full native-image contract and troubleshooting notes.

---

## Logging

CLI output is concise by default. Execution progress goes to stdout in `--no-tui` mode, expected
errors go to stderr, and internal executor logging uses SLF4J/Logback. Passwords and sudo input must
not be logged.

---

## Troubleshooting

| Symptom | Action |
|---|---|
| `mill: command not found` | Run commands from `sysboot/` with `./mill`, or set `MILL=/path/to/mill` when using `just`. |
| `native-image: command not found` | Install GraalVM 25+ with `native-image`. |
| Config exits with code 3 | Run `sysboot validate -c <file>` and fix the reported YAML/schema issue. |
| Package commands fail | Re-run with `--no-tui` to inspect command output and verify the package manager is installed. |
| Native image misses reflection metadata | Re-run with the native-image agent as described in `CONTRIBUTING.md`, then merge `graal/` updates. |

---

## Release Process

1. Run `just verify` from the repository root.
2. Build the assembly with `cd sysboot && ./mill cli.assembly`.
3. Build the native executable with `cd sysboot && ./mill cli.nativeImage`.
4. Smoke test `./out/cli/nativeImage.dest/sysboot --help` and `validate` against example configs.
5. Package the binary with README, license metadata, and example configs.

See `docs/release.md` for a fuller release checklist.

---

## TUI Note

The TUI requires `dev.tamboui:tamboui-tui:0.3.0-SNAPSHOT` from the Sonatype snapshots repository. If TamboUI is unavailable, build with `--no-tui` and use plain stdout mode. The `--no-tui` flag has no dependency on TamboUI.
