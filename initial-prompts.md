# Agent Prompt: Build `sysboot` — System Bootstrap CLI/TUI

## Mission

You are building **`sysboot`**: a native binary CLI/TUI application that replaces a collection of hand-run shell scripts for bootstrapping Linux development environments across Fedora, Arch Linux, and openSUSE. The reference shell-script implementation lives at https://github.com/w0rxbend/system-bootstrap — study its structure, package lists, and script flow to understand **what** the tool must accomplish, but do **not** replicate its code or structure: you are building the clean, maintainable Java replacement.

The conceptual inspiration for the user experience is [chezmoi](https://www.chezmoi.io/) — a single binary, config-driven, idempotent, interactive.

---

## Mandatory Pre-Reading

Before writing any code, read and internalize the custom skill file supplied alongside this prompt:

```
SKILL-java-mill-graal-tui.md
```

Every architectural decision, naming convention, code quality rule, and technology choice in that skill file is **non-negotiable**. When in doubt, re-read it.

---

## Deliverable Overview

A single GraalVM native binary called `sysboot` that:

1. Reads a YAML configuration file declaring an OS target and a set of ordered modules.
2. Presents an interactive TUI (TamboUI + JLine3) with live progress when run in a terminal.
3. Falls back to plain stdout output with `--no-tui`.
4. Installs packages, runs shell scripts, downloads/installs binaries, and adds Flatpak apps — with full per-item error isolation.
5. Handles interactive sudo prompts without leaving the TUI.
6. Supports Fedora (DNF), Arch (pacman/paru/yay), openSUSE (zypper — treat as DNF variant for now), and APT (Debian/Ubuntu, future-compatible).

---

## Phase 1: Project Scaffold & Build System

### 1.1 Repository layout

Create the following directory/file structure:

```
sysboot/
├── .mill-version                 # exact Mill version, e.g. "0.12.3"
├── build.sc                      # full Mill build definition
├── config/
│   ├── example-fedora.yaml
│   ├── example-arch.yaml
│   └── example-opensuse.yaml
├── graal/
│   ├── reflect-config.json       # initially empty array []
│   ├── resource-config.json      # initially empty
│   └── proxy-config.json
├── core/src/dev/sysboot/core/
├── config-parser/src/dev/sysboot/config/
├── executor/src/dev/sysboot/executor/
├── tui/src/dev/sysboot/tui/
├── cli/src/dev/sysboot/cli/
├── app/src/dev/sysboot/app/
└── integration-tests/src/dev/sysboot/it/
```

### 1.2 `build.sc`

Write a complete `build.sc` with:

- A `trait CommonJava` mixin for shared `javacOptions`, `scalacOptions` (n/a — Java only), Java version, repositories (including Sonatype snapshots for TamboUI).
- Six application modules: `core`, `configParser`, `executor`, `tui`, `cli`, `app` — each extending `CommonJava with JavaModule`.
- Dependency graph strictly: `cli → app → tui → executor → configParser → core`.
- An `assembly` task in `app` producing a fat JAR.
- A `nativeImage` task in `app` invoking `native-image` with all required flags (see skill §3).
- Picocli annotation processor wired in `cli` module's `compileIvyDeps`.
- Test sub-modules for each module using JUnit 5.
- An `integrationTests` module depending on `app` with a guard annotation.

### 1.3 Key dependency versions

Pin these in `build.sc` as `val` constants at the top:

| Library | Artifact | Version |
|---|---|---|
| Picocli | `info.picocli:picocli` | `4.7.6` |
| Picocli codegen | `info.picocli:picocli-codegen` | `4.7.6` |
| TamboUI TUI | `dev.tamboui:tamboui-tui` | `0.3.0-SNAPSHOT` |
| TamboUI picocli | `dev.tamboui:tamboui-picocli` | `0.3.0-SNAPSHOT` |
| TamboUI JLine3 | `dev.tamboui:tamboui-jline3-backend` | `0.3.0-SNAPSHOT` |
| Jackson YAML | `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` | `2.17.2` |
| Jackson databind | `com.fasterxml.jackson.core:jackson-databind` | `2.17.2` |
| pty4j | `org.jetbrains.pty4j:pty4j` | `0.12.30` |
| SLF4J API | `org.slf4j:slf4j-api` | `2.0.13` |
| Logback | `ch.qos.logback:logback-classic` | `1.5.6` |
| JUnit 5 | `org.junit.jupiter:junit-jupiter` | `5.11.0` |
| AssertJ | `org.assertj:assertj-core` | `3.26.3` |
| Mockito | `org.mockito:mockito-core` | `5.12.0` |

---

## Phase 2: Domain Model (`core` module)

Implement every type listed in skill §5 exactly as specified. Additionally:

### 2.1 Value objects (all records)
- `PackageName` — non-blank, trimmed string; rejects if contains shell metacharacters (space, `$`, `;`, `|`, `&`, `` ` ``, `>`, `<`).
- `ScriptPath` — must be absolute or resolvable relative to config file location.
- `BinaryUrl` — validates `https://` scheme only (no `http://`).
- `ProfileName` — non-blank string identifier.
- `ModuleName` — non-blank string identifier.

### 2.2 Sealed module hierarchy

```java
public sealed interface BootstrapModule permits
    PackageModule, FlatpakModule, ShellScriptModule, CompiledBinaryModule, ZypperModule {}
```

Add `ZypperModule` as alias for openSUSE — same structure as `PackageModule` but `packageManager = ZYPPER`.

### 2.3 `StepResult` sealed hierarchy

```java
public sealed interface StepResult permits
    StepResult.Success, StepResult.Failure, StepResult.Skipped, StepResult.DryRun {}

public sealed interface StepResult {
    record Success(String item, Duration elapsed) implements StepResult {}
    record Failure(String item, String errorMessage, int exitCode, Duration elapsed) implements StepResult {}
    record Skipped(String item, String reason) implements StepResult {}
    record DryRun(String item, List<String> wouldExecute) implements StepResult {}
}
```

### 2.4 `ExecutionEvent`

```java
public record ExecutionEvent(
    ModuleName moduleName,
    String item,
    EventKind kind,
    StepResult result,   // null for STARTED events
    Instant timestamp
) {}

public enum EventKind { MODULE_STARTED, ITEM_STARTED, ITEM_COMPLETED, MODULE_COMPLETED, ERROR }
```

### 2.5 `BootstrapConfig` with builder

`BootstrapConfig` must be immutable. Provide a nested `Builder` with validation in `build()`. Validation rules:
- At least one module required.
- All module names unique within a profile.
- No circular dependencies (if dependency ordering is added later — reserve the field).

---

## Phase 3: Config Parser (`config-parser` module)

### 3.1 YAML schema (implement exactly this)

```yaml
profile: <string>
os:
  type: fedora | arch | opensuse | debian   # maps to OsTarget sealed subtype
  release: <string>                          # e.g. "41" for Fedora, optional for Arch

modules:
  - type: packages
    name: <string>
    packageManager: dnf | pacman | paru | yay | apt | zypper
    continueOnError: true | false           # default: true
    packages:
      - <package-name>
      - ...

  - type: flatpak
    name: <string>
    remote: flathub                         # default: flathub
    appIds:
      - <app.id>

  - type: shell-script
    name: <string>
    script: <relative-or-absolute-path>
    args:
      - <arg>
    workingDir: <path>                      # default: config file directory
    continueOnError: false

  - type: compiled-binary
    name: <string>
    binaryName: <string>                    # name for display
    url: <https-url>
    checksum:
      algorithm: sha256
      value: <hex>
    installPath: <absolute-path>
    continueOnError: false
```

### 3.2 `YamlConfigLoader` implementation

- Use `ObjectMapper` with `YAMLFactory`.
- Map YAML to an intermediate `ConfigDto` record graph (Jackson-annotated DTOs — keep separate from domain).
- Translate `ConfigDto → BootstrapConfig` in a `ConfigMapper` class.
- Validate paths: `ScriptPath` existence checked at load time (warn but do not fail — scripts may be relative to repo root).
- Return `Either<ConfigLoadError, BootstrapConfig>` or throw a typed `ConfigLoadException` — choose one and be consistent.
- Register all DTO types in `graal/reflect-config.json`.

### 3.3 Example config files

Write three complete example configs:
- `config/example-fedora.yaml`: mirrors what `scripts/fedora/01-packages.sh` would install — core CLI tools, dev tools, fonts, flatpaks.
- `config/example-arch.yaml`: uses `pacman` + `paru` for AUR.
- `config/example-opensuse.yaml`: uses `zypper`.

---

## Phase 4: Executor Module (`executor` module)

### 4.1 `ShellRunner` port and implementation

```java
public interface ShellRunner {
    ProcessResult run(List<String> command, Map<String, String> env, Duration timeout);
}

public record ProcessResult(int exitCode, String stdout, String stderr, Duration elapsed) {}
```

Implement `DefaultShellRunner` using `ProcessBuilder`:
- Merge stderr by default (configurable).
- Honour timeout via `process.waitFor(timeout)`, destroy forcibly if exceeded.
- **Never** pass commands through a shell (`/bin/sh -c "..."`) — always pass as list to avoid injection.
- Log command at `DEBUG` level before execution (mask password arguments).

Implement `PtyShellRunner` for commands needing PTY (sudo):
- Use `pty4j` `PtyProcess`.
- Monitor output stream for sudo password prompt patterns: `"\[sudo\] password"`, `"Password:"`.
- When detected, call `SudoPasswordProvider.requestPassword()`, write password + `\n` to PTY stdin.
- Zero the char array immediately after.
- Return `ProcessResult` when process exits.

### 4.2 Package manager executors

Implement all of these following the `DnfPackageInstaller` pattern in the skill file:

| Class | Manager | Install command |
|---|---|---|
| `DnfPackageInstaller` | DNF | `sudo dnf install -y <pkg>` (one per invocation) |
| `PacmanPackageInstaller` | pacman | `sudo pacman -S --noconfirm <pkg>` |
| `ParuPackageInstaller` | paru | `paru -S --noconfirm <pkg>` (no sudo — paru handles it) |
| `YayPackageInstaller` | yay | `yay -S --noconfirm <pkg>` |
| `AptPackageInstaller` | APT | `sudo apt-get install -y <pkg>` |
| `ZypperPackageInstaller` | zypper | `sudo zypper install -y <pkg>` |
| `FlatpakInstaller` | Flatpak | `flatpak install -y <remote> <appId>` |

**Critical**: each package is a separate process call. Do not batch. Emit `ExecutionEvent` before and after each.

### 4.3 `ShellScriptExecutor`

Executes a `ShellScriptModule`:
- Detect interpreter from shebang line (fall back to `/bin/bash`).
- Make script executable if not already (`Files.setPosixFilePermissions`).
- Run via `PtyShellRunner` (scripts may prompt for sudo).
- Stream output lines as `ExecutionEvent` items.

### 4.4 `CompiledBinaryInstaller`

Executes a `CompiledBinaryModule`:
- Download URL to temp file using `HttpClient` (Java 11+).
- Verify checksum if provided (SHA-256 via `MessageDigest`).
- Detect archive type from URL: `.tar.gz`, `.tar.xz`, `.zip`, plain binary.
- Extract with Java NIO (no external `tar` process — use Apache Commons Compress or a pure-Java extractor; add as dependency).
- Copy to `installPath` (may need `sudo cp` if root-owned).
- Clean up temp files.

### 4.5 `BootstrapOrchestratorImpl` (in `executor`)

Implements `BootstrapOrchestrator` port:

```java
public final class BootstrapOrchestratorImpl implements BootstrapOrchestrator {
    // injected:
    private final PackageManagerExecutorRegistry executorRegistry;
    private final ShellScriptExecutor shellScriptExecutor;
    private final CompiledBinaryInstaller binaryInstaller;
    private final FlatpakInstaller flatpakInstaller;

    @Override
    public void execute(BootstrapConfig config, ExecutionEventListener listener) {
        for (BootstrapModule module : config.modules()) {
            listener.onEvent(moduleStarted(module));
            executeModule(module, listener);
            listener.onEvent(moduleCompleted(module));
        }
    }
}
```

Module dispatch via pattern-matching switch (Java 21+):
```java
private void executeModule(BootstrapModule module, ExecutionEventListener listener) {
    switch (module) {
        case PackageModule pm -> executePackageModule(pm, listener);
        case FlatpakModule fm -> executeFlatpakModule(fm, listener);
        case ShellScriptModule sm -> executeShellScript(sm, listener);
        case CompiledBinaryModule bm -> executeBinaryInstall(bm, listener);
        case ZypperModule zm -> executePackageModule(asPackageModule(zm), listener);
    }
}
```

### 4.6 `PackageManagerExecutorRegistry`

```java
public final class PackageManagerExecutorRegistry {
    private final List<PackageManagerExecutor> executors;

    public PackageManagerExecutor forKind(PackageManagerKind kind) {
        return executors.stream()
            .filter(e -> e.supports(kind))
            .findFirst()
            .orElseThrow(() -> new UnsupportedPackageManagerException(kind));
    }
}
```

---

## Phase 5: TUI Module (`tui` module)

### 5.1 Screen state machine

```java
public sealed interface AppState permits
    AppState.Dashboard, AppState.ModuleList, AppState.Executing, AppState.Logs,
    AppState.SudoPrompt, AppState.Completed, AppState.Error {}
```

`TuiController` manages transitions:
- Start → `Dashboard` → user selects profile → `ModuleList` → user confirms → `Executing`.
- During `Executing`: any sudo request → overlay `SudoPrompt` → back to `Executing`.
- Execution ends → `Completed` or `Error`.
- `l` key → `Logs` from any executing state.
- `q` from `Completed`/`Error` → exit.

### 5.2 `ExecutionScreen`

State: `ExecutionScreenState` (record):
```java
public record ExecutionScreenState(
    String profileName,
    String currentModule,
    int totalModules,
    int completedModules,
    List<ItemStatus> items,   // recent N items, scrollable
    List<String> logLines,
    boolean paused
) {}

public record ItemStatus(String name, String module, ItemResult result, Duration elapsed) {}
public enum ItemResult { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED }
```

Rendering:
- Top bar: profile name, OS, current module, overall progress gauge.
- Main area: table of recent item statuses with colored status indicators.
- Bottom bar: key bindings.
- Right pane (optional, `v` to toggle): last 20 log lines.

Use TamboUI `Gauge`, `Table`, `Paragraph`, `Block`, `Tabs` widgets.
Use `Color.GREEN` for success, `Color.RED` for failure, `Color.YELLOW` for running, `Color.WHITE` for pending.

### 5.3 `SudoPromptScreen` (modal overlay)

- Renders over the `ExecutionScreen` using TamboUI `Clear` + `Block`.
- Contains a `TextInput` widget in password mode (mask characters with `*`).
- On `Enter`: submits password to `SudoPasswordProvider`; clears `TextInput` state; zeroes the char array.
- On `Esc`: cancels (calls `SudoPasswordProvider` with empty to abort the step).
- The `SudoPasswordProvider` implementation uses a `SynchronousQueue<char[]>` — TUI writes to it, `PtyShellRunner` blocks reading from it.

### 5.4 `TuiExecutionEventListener`

Implements `ExecutionEventListener`. Receives events on executor virtual threads, posts them to a `LinkedBlockingQueue<ExecutionEvent>`. The TUI `TickEvent` handler drains this queue (max 50 events per tick) and updates `ExecutionScreenState`. No locking needed — single consumer.

### 5.5 `DashboardScreen`

Shows:
- Detected OS (read from `/etc/os-release`).
- Available config profiles found in `~/.config/sysboot/`.
- A selectable list of profiles.
- `Enter` to proceed to `ModuleListScreen`.

### 5.6 `ModuleListScreen`

Shows all modules in the selected profile with:
- Module name, type (icon: 📦 packages, 📜 scripts, ⬇ binary, 🗃 flatpak).
- Package count or script name.
- Checkboxes: user can deselect modules to skip.
- `Enter` to start execution, `Esc` to go back.

---

## Phase 6: CLI Module (`cli` module)

### 6.1 Command structure

```java
@Command(
    name = "sysboot",
    mixinStandardHelpOptions = true,
    version = "sysboot 1.0.0",
    description = "Bootstrap your Linux system from a declarative config",
    subcommands = {
        RunCommand.class,
        DryRunCommand.class,
        ValidateCommand.class,
        ListCommand.class,
        StatusCommand.class,
        GenerateCommand.class
    }
)
public final class SysbootCommand extends TuiCommand {
    @Option(names = {"-c", "--config"}, description = "Config file [default: ~/.config/sysboot/default.yaml]")
    private Path configFile;

    @Option(names = {"--no-tui"}, description = "Disable TUI, print to stdout")
    private boolean noTui;

    @Option(names = {"-v", "--verbose"})
    private boolean verbose;
}
```

### 6.2 `RunCommand`

```java
@Command(name = "run", description = "Execute a bootstrap profile")
public final class RunCommand implements Runnable {
    @ParentCommand private SysbootCommand parent;

    @Option(names = {"--modules"}, description = "Comma-separated list of modules to run (default: all)")
    private String moduleFilter;

    @Option(names = {"--dry-run"}, description = "Show what would be done")
    private boolean dryRun;

    @Override
    public void run() { /* delegate to ApplicationContext */ }
}
```

### 6.3 `ValidateCommand`

Parse config, run `BootstrapConfig.validate()`, print errors or "✓ Config is valid".

### 6.4 `GenerateCommand`

```
sysboot generate --os fedora --profile my-profile
```

Interactive TUI wizard to generate a starter YAML config file.

### 6.5 Stdout fallback mode (`--no-tui`)

When `--no-tui`: `ExecutionEventListener` implementation writes to `System.out` with ANSI color codes (use picocli's `Help.Ansi`). Same event-driven architecture — just a different listener.

---

## Phase 7: Application Wiring (`app` module)

### 7.1 `ApplicationContext`

Single class, no framework. Pure constructor injection wiring:

```java
public final class ApplicationContext {

    private final BootstrapOrchestrator orchestrator;
    private final ConfigLoader configLoader;
    private final SudoPasswordProvider sudoPasswordProvider;
    // ...

    private ApplicationContext(
        ShellRunner shellRunner,
        SudoPasswordProvider sudoPasswordProvider,
        ExecutionEventListener eventListener
    ) {
        var dnfInstaller = new DnfPackageInstaller(shellRunner, sudoPasswordProvider);
        var pacmanInstaller = new PacmanPackageInstaller(shellRunner, sudoPasswordProvider);
        // ... more installers
        var registry = new PackageManagerExecutorRegistry(List.of(
            dnfInstaller, pacmanInstaller, /* ... */
        ));
        var shellScriptExecutor = new ShellScriptExecutor(new PtyShellRunner(sudoPasswordProvider));
        var binaryInstaller = new CompiledBinaryInstaller(shellRunner);
        var flatpakInstaller = new FlatpakInstaller(shellRunner);
        this.orchestrator = new BootstrapOrchestratorImpl(registry, shellScriptExecutor, binaryInstaller, flatpakInstaller);
        this.configLoader = new YamlConfigLoader();
        this.sudoPasswordProvider = sudoPasswordProvider;
    }

    public static ApplicationContext forTui(TuiSudoPasswordProvider tuiProvider, TuiExecutionEventListener tuiListener) {
        return new ApplicationContext(new PtyShellRunner(tuiProvider), tuiProvider, tuiListener);
    }

    public static ApplicationContext forCli(ExecutionEventListener stdoutListener) {
        var noopSudo = prompt -> Optional.<char[]>empty(); // will be prompted in stdout
        return new ApplicationContext(new DefaultShellRunner(), noopSudo, stdoutListener);
    }
}
```

### 7.2 `Main`

```java
public final class Main {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new SysbootCommand()).execute(args);
        System.exit(exitCode);
    }
}
```

---

## Phase 8: GraalVM Native Image

### 8.1 Reflection config generation

Run tests with the native-image agent to capture all reflective access:

```bash
java -agentlib:native-image-agent=config-output-dir=graal/ \
     -jar app/out/assembly.dest/out.jar validate -c config/example-fedora.yaml
```

Merge the generated configs into `graal/reflect-config.json` etc.

### 8.2 Known GraalVM flags for this stack

```
--no-fallback
--enable-preview
--initialize-at-build-time=org.slf4j,ch.qos.logback
--initialize-at-run-time=org.jline
-H:+ReportExceptionStackTraces
-H:ReflectionConfigurationFiles=graal/reflect-config.json
-H:ResourceConfigurationFiles=graal/resource-config.json
-H:DynamicProxyConfigurationFiles=graal/proxy-config.json
--features=picocli.codegen.aot.ondemand.OnDemandGenerationFeature
```

### 8.3 Final binary

- Output: `sysboot` (no extension).
- Target: Linux x86_64 (primary); aarch64 as secondary target.
- Size goal: < 50 MB (achievable with JLine + TamboUI + Picocli without Spring).
- Startup goal: < 100ms.

---

## Phase 9: Testing

### 9.1 Unit tests required (non-negotiable)

Write tests for every class in the following priority order:

1. All value objects (`PackageName`, `ScriptPath`, `BinaryUrl`) — boundary/invalid input.
2. `BootstrapConfig.Builder` — validation rules.
3. `YamlConfigLoader` — parse all three example configs; parse invalid YAML; parse missing fields.
4. `ConfigMapper` — test each module type mapping.
5. `DnfPackageInstaller` — mock `ShellRunner`; verify exact command, `continueOnError` behavior.
6. `PacmanPackageInstaller`, `AptPackageInstaller` — same pattern.
7. `BootstrapOrchestratorImpl` — mock all executors; verify event ordering; verify module skipping.
8. `TuiExecutionEventListener` — verify events drain correctly.
9. `PackageManagerExecutorRegistry` — registry lookup; unknown kind throws.

### 9.2 Test naming

```java
// Pattern: <methodOrScenario>_<condition>_<expectedResult>
@Test void install_whenPackageNameIsBlank_throwsIllegalArgumentException() { ... }
@Test void install_whenDnfExitsNonZero_returnsFailureResult() { ... }
@Test void load_whenYamlHasAllModuleTypes_parsesAllModulesCorrectly() { ... }
```

### 9.3 Test data

Create a `test/resources/` directory in each module with:
- `valid-fedora.yaml`, `valid-arch.yaml`, `valid-opensuse.yaml`
- `missing-os-field.yaml`, `empty-modules.yaml`, `invalid-package-name.yaml`

---

## Phase 10: Documentation

### 10.1 `README.md`

Must contain:
- What `sysboot` is (one paragraph).
- Prerequisites (GraalVM 24+, Java 24, Mill 0.12+).
- Quick start: clone → build native → run.
- Config file format (full schema reference with all fields and types).
- All CLI commands with examples.
- How to add a new profile.
- How to add support for a new package manager (the Open/Closed extension point).
- Architecture diagram (ASCII, showing modules and dependency flow).

### 10.2 `CONTRIBUTING.md`

- Code style rules (reference the skill file).
- How to run tests.
- How to run the TUI demo without native image.
- PR checklist.

### 10.3 Config schema doc

`docs/config-schema.md` — full annotated YAML schema with all fields, types, defaults, and validation rules.

---

## Execution Order for the Agent

Implement phases in this exact order. Do not proceed to the next phase until the current phase compiles and its tests pass.

```
Phase 1 → scaffold + build.sc compiles
Phase 2 → core domain model; all unit tests green
Phase 3 → config parser; YAML round-trips for all 3 example configs
Phase 4 → executor module; unit tests with mocked ShellRunner
Phase 5 → tui module; renders screens in headless test mode
Phase 6 → cli module; picocli parses all commands correctly
Phase 7 → app module; wiring compiles; integration test runs dry-run end-to-end
Phase 8 → native-image build produces working binary
Phase 9 → full test suite passes
Phase 10 → documentation complete
```

---

## Hard Constraints Checklist

Before considering any phase complete, verify:

- [ ] No class exceeds 300 lines.
- [ ] No method exceeds 20 lines.
- [ ] No `null` returned from any public method — `Optional` or sealed Result types used.
- [ ] All collections returned are unmodifiable (`List.of`, `List.copyOf`, `Collections.unmodifiableList`).
- [ ] No static mutable state.
- [ ] No field injection — constructor injection only.
- [ ] No reflection except what is registered in `graal/reflect-config.json`.
- [ ] No raw `String` used where a value object exists.
- [ ] No `catch (Exception e)` — specific exception types only.
- [ ] No comments explaining *what* — only *why*.
- [ ] All package manager installations are per-package separate process calls.
- [ ] Passwords held as `char[]`, zeroed after use, never in logs.
- [ ] `--no-tui` mode works without any TamboUI dependency being initialised.
- [ ] `dry-run` mode makes zero system changes.
- [ ] All tests use the naming convention `<scenario>_<condition>_<expected>`.
- [ ] Mill `nativeImage` task produces a working binary under 50 MB.

---

## What NOT to Do

- Do NOT use Spring, Quarkus, Micronaut, or any DI framework.
- Do NOT use runtime reflection for DI wiring.
- Do NOT run `sh -c "full command string"` — always pass command as `List<String>`.
- Do NOT swallow exceptions silently.
- Do NOT log passwords, sudo output containing passwords, or temp file paths with sensitive names.
- Do NOT use `Thread.sleep` for synchronisation — use `BlockingQueue`, `CountDownLatch`, `SynchronousQueue`.
- Do NOT put domain logic in the CLI layer.
- Do NOT put TUI logic in the executor layer.
- Do NOT use Gradle wrapper or Maven wrapper — Mill only.
- Do NOT use deprecated TamboUI APIs (`tamboui-aesh-backend` — use JLine3 backend).

---

## Reference Material

- System bootstrap repo (shell script reference): https://github.com/w0rxbend/system-bootstrap
- TamboUI: https://github.com/tamboui/tamboui
- TamboUI picocli integration example: `tamboui-picocli` demo in the repo
- Picocli docs: https://picocli.info/
- Picocli + GraalVM: https://picocli.info/picocli-on-graalvm.html
- Mill build tool: https://mill-build.org/mill/
- GraalVM native image: https://www.graalvm.org/latest/reference-manual/native-image/
- pty4j (PTY Java): https://github.com/JetBrains/pty4j
- Jackson YAML: https://github.com/FasterXML/jackson-dataformats-text/tree/master/yaml
- chezmoi (UX inspiration): https://www.chezmoi.io/


# Addendum: Idempotency, State Tracking & `--skip-already-installed`

> This document extends `AGENT-PROMPT-sysboot.md`.
> All rules from that document and `SKILL-java-mill-graal-tui.md` remain in force.
> Implement everything in this addendum as part of Phase 2–9 expansions.

---

## Overview & Design Philosophy

`sysboot` must be safe to run multiple times. Re-running a bootstrap profile on an already-configured machine must never reinstall things that are working, never re-run scripts that already completed, and never re-download binaries that are already at the correct version.

There are two complementary mechanisms:

1. **State file** (`~/.local/share/sysboot/<profile-name>.state.json`) — a persistent record of every item that has been successfully installed in a previous `sysboot` run. This is the authoritative source. If it says "installed", trust it.

2. **Live probe** — for each item type, a dedicated `InstalledProbe` that asks the OS right now whether the item is present. Used when (a) no state file exists, (b) the item is not in the state file, or (c) the user passes `--re-probe`.

The flag `--skip-already-installed` activates both mechanisms. Without it, `sysboot` runs everything unconditionally (useful for force-reinstall / CI).

---

## 1. New Domain Types (`core` module additions)

### 1.1 `InstallationStatus` sealed hierarchy

```java
public sealed interface InstallationStatus permits
    InstallationStatus.NotInstalled,
    InstallationStatus.InstalledFromState,
    InstallationStatus.InstalledByProbe,
    InstallationStatus.Unknown {}

public sealed interface InstallationStatus {

    /** State file says this item was successfully installed by sysboot. */
    record InstalledFromState(
        String item,
        Instant installedAt,
        String version          // may be null if version unknown at install time
    ) implements InstallationStatus {}

    /** Live OS probe confirms this item is present. */
    record InstalledByProbe(
        String item,
        String detectedVersion  // may be null
    ) implements InstallationStatus {}

    /** Neither state file nor probe can confirm presence. */
    record NotInstalled(String item) implements InstallationStatus {}

    /** Probe failed (e.g. probe command itself errored). Treat as NotInstalled. */
    record Unknown(String item, String reason) implements InstallationStatus {}
}
```

### 1.2 `StateEntry` — one record per successfully installed item

```java
public record StateEntry(
    String profileName,
    String moduleName,
    String itemKey,          // unique identifier: package name, app ID, script path, binary name
    ItemType itemType,
    Instant completedAt,
    String version,          // detected version at install time; may be null
    String checksum          // for compiled-binaries: sha256 of installed file; may be null
) {}

public enum ItemType { PACKAGE, FLATPAK, SHELL_SCRIPT, COMPILED_BINARY }
```

### 1.3 `BootstrapState` — the full state file model

```java
public record BootstrapState(
    String profileName,
    Instant lastRunAt,
    String sysBbotVersion,
    List<StateEntry> entries
) {
    /** Returns the entry for a given itemKey, if present. */
    public Optional<StateEntry> findEntry(String itemKey, ItemType type) {
        return entries.stream()
            .filter(e -> e.itemKey().equals(itemKey) && e.itemType() == type)
            .findFirst();
    }
}
```

### 1.4 `SkipDecision` — result of the skip-check decision

```java
public sealed interface SkipDecision permits
    SkipDecision.Skip, SkipDecision.Run {}

public sealed interface SkipDecision {
    record Skip(String itemKey, InstallationStatus reason) implements SkipDecision {}
    record Run(String itemKey) implements SkipDecision {}
}
```

---

## 2. New Port: `InstalledProbe` (`core` module)

Each item type has a dedicated probe strategy. The probe never installs anything — it only reads.

```java
/**
 * Checks whether a given item is already installed on the current OS,
 * without using the sysboot state file.
 *
 * Implementations must be idempotent, read-only, and fast (< 2s per probe).
 */
public interface InstalledProbe {

    /**
     * Returns whether this probe supports the given item type.
     */
    boolean supports(ItemType itemType);

    /**
     * Probe the OS for the presence of the item identified by itemKey.
     *
     * @param itemKey  For packages: the package name.
     *                 For flatpaks: the app ID.
     *                 For shell scripts: the script path (as string).
     *                 For compiled binaries: the install path (as string).
     */
    InstallationStatus probe(String itemKey);
}
```

### 2.1 New Port: `StateRepository`

```java
public interface StateRepository {
    Optional<BootstrapState> load(String profileName);
    void save(BootstrapState state);
    void recordSuccess(String profileName, StateEntry entry);
    void recordFailure(String profileName, String itemKey, ItemType itemType, String reason);
}
```

---

## 3. Probe Implementations (`executor` module)

### 3.1 Package probes

One probe class per package manager — they all implement `InstalledProbe`:

| Class | Manager | Probe command | Installed if |
|---|---|---|---|
| `DnfPackageProbe` | DNF | `rpm -q <pkg>` | exit code 0 |
| `PacmanPackageProbe` | pacman/paru/yay | `pacman -Q <pkg>` | exit code 0 |
| `AptPackageProbe` | APT | `dpkg-query -W -f='${Status}' <pkg>` | stdout contains `install ok installed` |
| `ZypperPackageProbe` | zypper | `rpm -q <pkg>` | exit code 0 (zypper uses RPM db) |

Each uses `DefaultShellRunner` (not `PtyShellRunner` — probes need no sudo).

Example:
```java
public final class DnfPackageProbe implements InstalledProbe {

    private final ShellRunner shellRunner;

    public DnfPackageProbe(ShellRunner shellRunner) {
        this.shellRunner = shellRunner;
    }

    @Override
    public boolean supports(ItemType itemType) {
        return itemType == ItemType.PACKAGE;
    }

    @Override
    public InstallationStatus probe(String packageName) {
        var result = shellRunner.run(
            List.of("rpm", "-q", packageName),
            Map.of(),
            Duration.ofSeconds(10)
        );

        if (result.exitCode() == 0) {
            // rpm -q output: "packagename-version-release.arch"
            var version = extractVersionFromRpmOutput(result.stdout().trim());
            return new InstallationStatus.InstalledByProbe(packageName, version);
        }

        // exit code 1 = not installed; anything else = query error
        if (result.exitCode() == 1) {
            return new InstallationStatus.NotInstalled(packageName);
        }

        return new InstallationStatus.Unknown(packageName,
            "rpm -q exited with code %d: %s".formatted(result.exitCode(), result.stderr()));
    }

    private String extractVersionFromRpmOutput(String rpmLine) {
        // "git-2.45.2-1.fc41.x86_64" → "2.45.2-1.fc41.x86_64"
        int firstDash = rpmLine.indexOf('-');
        return firstDash >= 0 ? rpmLine.substring(firstDash + 1) : null;
    }
}
```

### 3.2 `FlatpakProbe`

```java
public final class FlatpakProbe implements InstalledProbe {

    private final ShellRunner shellRunner;

    @Override
    public boolean supports(ItemType itemType) { return itemType == ItemType.FLATPAK; }

    @Override
    public InstallationStatus probe(String appId) {
        // flatpak list --app --columns=application returns one app ID per line
        var result = shellRunner.run(
            List.of("flatpak", "list", "--app", "--columns=application"),
            Map.of(),
            Duration.ofSeconds(15)
        );

        if (result.exitCode() != 0) {
            return new InstallationStatus.Unknown(appId, "flatpak list failed: " + result.stderr());
        }

        boolean found = result.stdout().lines()
            .map(String::trim)
            .anyMatch(appId::equals);

        return found
            ? new InstallationStatus.InstalledByProbe(appId, null)
            : new InstallationStatus.NotInstalled(appId);
    }
}
```

### 3.3 `CompiledBinaryProbe`

Checks two things: the binary exists at `installPath` AND (if a checksum was recorded in state) the SHA-256 matches.

```java
public final class CompiledBinaryProbe implements InstalledProbe {

    @Override
    public boolean supports(ItemType itemType) { return itemType == ItemType.COMPILED_BINARY; }

    @Override
    public InstallationStatus probe(String installPath) {
        var path = Path.of(installPath);

        if (!Files.exists(path)) {
            return new InstallationStatus.NotInstalled(installPath);
        }

        if (!Files.isExecutable(path)) {
            return new InstallationStatus.Unknown(installPath,
                "File exists but is not executable: " + installPath);
        }

        // Try to get version via --version flag (best-effort, not required)
        String version = tryGetVersion(path);
        return new InstallationStatus.InstalledByProbe(installPath, version);
    }

    private String tryGetVersion(Path binary) {
        // Many binaries support --version; ignore errors
        try {
            var result = new ProcessBuilder(binary.toString(), "--version")
                .redirectErrorStream(true)
                .start();
            var stdout = new String(result.getInputStream().readAllBytes()).trim();
            result.waitFor(3, TimeUnit.SECONDS);
            // Extract first line, first token that looks like a version number
            return stdout.lines().findFirst()
                .map(line -> line.replaceAll(".*?(\\d+\\.\\d+[\\w.-]*).*", "$1"))
                .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }
}
```

### 3.4 `ShellScriptProbe`

Shell scripts are the trickiest — there is no universal way to probe them. The strategy:

1. **Config-declared probe command**: the YAML config for a `shell-script` module can declare an explicit `probeCommand`. If present, run it; exit 0 = already done.
2. **State file only**: if no `probeCommand` declared, only trust the state file. Never assume "script already ran" from filesystem alone.

```java
public final class ShellScriptProbe implements InstalledProbe {

    private final ShellRunner shellRunner;
    // Map from script path → probe command, populated from config
    private final Map<String, String> probeCommands;

    @Override
    public boolean supports(ItemType itemType) { return itemType == ItemType.SHELL_SCRIPT; }

    @Override
    public InstallationStatus probe(String scriptPath) {
        var probeCommand = probeCommands.get(scriptPath);

        if (probeCommand == null) {
            // No probe command declared — cannot live-probe shell scripts
            // Caller must rely on state file only
            return new InstallationStatus.Unknown(scriptPath,
                "No probe command configured for this script. " +
                "Add 'probeCommand' to the shell-script module in your config.");
        }

        var result = shellRunner.run(
            List.of("/bin/sh", "-c", probeCommand),
            Map.of(),
            Duration.ofSeconds(30)
        );

        return result.exitCode() == 0
            ? new InstallationStatus.InstalledByProbe(scriptPath, null)
            : new InstallationStatus.NotInstalled(scriptPath);
    }
}
```

### 3.5 `InstalledProbeRegistry`

```java
public final class InstalledProbeRegistry {

    private final List<InstalledProbe> probes;

    public InstallationStatus probe(String itemKey, ItemType itemType) {
        return probes.stream()
            .filter(p -> p.supports(itemType))
            .findFirst()
            .map(p -> p.probe(itemKey))
            .orElse(new InstallationStatus.Unknown(itemKey,
                "No probe registered for item type: " + itemType));
    }
}
```

---

## 4. `SkipEvaluator` — the central skip-decision logic (`executor` module)

This is the single class responsible for answering: "should we skip this item?". It encapsulates the two-mechanism strategy.

```java
/**
 * Decides whether an item should be skipped based on:
 * 1. The state file (fast, no OS calls).
 * 2. A live OS probe (slower, always accurate).
 *
 * Decision precedence:
 * a) If skipAlreadyInstalled is false → always Run.
 * b) If item found in state file → Skip (InstalledFromState).
 * c) If live probe returns InstalledByProbe → Skip.
 * d) If live probe returns Unknown → Run (fail-safe: attempt installation).
 * e) If live probe returns NotInstalled → Run.
 */
public final class SkipEvaluator {

    private final Optional<BootstrapState> state;
    private final InstalledProbeRegistry probeRegistry;
    private final boolean skipAlreadyInstalled;
    private final boolean reProbe;  // --re-probe: ignore state file, always live-probe

    public SkipEvaluator(
        Optional<BootstrapState> state,
        InstalledProbeRegistry probeRegistry,
        boolean skipAlreadyInstalled,
        boolean reProbe
    ) {
        this.state = state;
        this.probeRegistry = probeRegistry;
        this.skipAlreadyInstalled = skipAlreadyInstalled;
        this.reProbe = reProbe;
    }

    public SkipDecision evaluate(String itemKey, ItemType itemType) {
        if (!skipAlreadyInstalled) {
            return new SkipDecision.Run(itemKey);
        }

        // Step 1: Check state file (unless --re-probe overrides)
        if (!reProbe) {
            var stateEntry = state.flatMap(s -> s.findEntry(itemKey, itemType));
            if (stateEntry.isPresent()) {
                var entry = stateEntry.get();
                return new SkipDecision.Skip(itemKey,
                    new InstallationStatus.InstalledFromState(
                        itemKey, entry.completedAt(), entry.version()));
            }
        }

        // Step 2: Live probe
        var probeResult = probeRegistry.probe(itemKey, itemType);
        return switch (probeResult) {
            case InstallationStatus.InstalledByProbe p ->
                new SkipDecision.Skip(itemKey, p);
            case InstallationStatus.InstalledFromState s ->
                new SkipDecision.Skip(itemKey, s);    // shouldn't happen from probe, but safe
            case InstallationStatus.NotInstalled n ->
                new SkipDecision.Run(itemKey);
            case InstallationStatus.Unknown u ->
                new SkipDecision.Run(itemKey);         // fail-safe
        };
    }
}
```

---

## 5. State File Persistence (`executor` module)

### 5.1 State file location

```
~/.local/share/sysboot/<profile-name>.state.json
```

- Per-profile state (not a single global file — allows running multiple profiles independently).
- JSON format (human-readable, easy to inspect and manually edit).
- The directory is created on first write.

### 5.2 `JsonStateRepository` implementation

```java
public final class JsonStateRepository implements StateRepository {

    private static final Path STATE_DIR =
        Path.of(System.getProperty("user.home"), ".local", "share", "sysboot");

    private final ObjectMapper objectMapper;  // Jackson, no YAML needed — plain JSON

    public JsonStateRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<BootstrapState> load(String profileName) {
        var stateFile = stateFilePath(profileName);
        if (!Files.exists(stateFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(stateFile.toFile(), BootstrapStateDto.class))
                .map(StateMapper::toDomain);
        } catch (IOException e) {
            // Corrupt state file: log a warning and treat as absent.
            // Do NOT fail the run — a corrupt state file should degrade gracefully.
            log.warn("Could not read state file {}: {}. Treating as absent.", stateFile, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void recordSuccess(String profileName, StateEntry entry) {
        // Load current state, add/replace the entry, save atomically.
        var current = load(profileName).orElse(emptyState(profileName));
        var updated = withEntry(current, entry);
        saveAtomically(profileName, updated);
    }

    private void saveAtomically(String profileName, BootstrapState state) {
        // Write to temp file, then atomically rename to final path.
        // This prevents a partial write from corrupting the state file.
        var finalPath = stateFilePath(profileName);
        var tempPath = finalPath.resolveSibling(finalPath.getFileName() + ".tmp");
        try {
            Files.createDirectories(STATE_DIR);
            objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(tempPath.toFile(), StateMapper.toDto(state));
            Files.move(tempPath, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StateWriteException("Failed to save state for profile: " + profileName, e);
        }
    }

    private Path stateFilePath(String profileName) {
        // Sanitize profile name to be safe as a filename
        var safeName = profileName.replaceAll("[^a-zA-Z0-9_.-]", "_");
        return STATE_DIR.resolve(safeName + ".state.json");
    }
}
```

**Key implementation details:**
- Atomic write via temp file + rename (`ATOMIC_MOVE`) — prevents corruption on crash mid-write.
- Corrupt state file → warn + treat as absent (never fail the run because of a bad state file).
- State file is **append-friendly**: each `recordSuccess` call loads the current file, merges the new entry, and saves. The entry for a given `(itemKey, itemType)` is replaced if it already exists (idempotent record).

### 5.3 State file JSON format

```json
{
  "profileName": "fedora-workstation",
  "lastRunAt": "2026-06-14T10:22:45Z",
  "sysbootVersion": "1.0.0",
  "entries": [
    {
      "moduleName": "core-cli-tools",
      "itemKey": "git",
      "itemType": "PACKAGE",
      "completedAt": "2026-06-14T10:22:46Z",
      "version": "2.45.2-1.fc41.x86_64",
      "checksum": null
    },
    {
      "moduleName": "core-cli-tools",
      "itemKey": "curl",
      "itemType": "PACKAGE",
      "completedAt": "2026-06-14T10:22:47Z",
      "version": "8.6.0-8.fc41.x86_64",
      "checksum": null
    },
    {
      "moduleName": "desktop-apps",
      "itemKey": "com.spotify.Client",
      "itemType": "FLATPAK",
      "completedAt": "2026-06-14T10:23:10Z",
      "version": null,
      "checksum": null
    },
    {
      "moduleName": "install-neovim",
      "itemKey": "/usr/local/bin/nvim",
      "itemType": "COMPILED_BINARY",
      "completedAt": "2026-06-14T10:25:01Z",
      "version": "0.10.1",
      "checksum": "a3f1e9b8c2d4..."
    },
    {
      "moduleName": "install-sdkman",
      "itemKey": "scripts/cli-tools.sh",
      "itemType": "SHELL_SCRIPT",
      "completedAt": "2026-06-14T10:26:30Z",
      "version": null,
      "checksum": null
    }
  ]
}
```

---

## 6. YAML Config Extensions (new fields)

### 6.1 `shell-script` module — `probeCommand` field

```yaml
- type: shell-script
  name: install-sdkman
  script: scripts/cli-tools.sh
  args: ["--sdkman"]
  probeCommand: "test -d $HOME/.sdkman && $HOME/.sdkman/bin/sdkman-init.sh version"
  continueOnError: false
```

The `probeCommand` is a shell expression. Exit 0 = already done, skip. Non-zero = not done, run script.

More examples:
```yaml
# Check if oh-my-zsh is installed
probeCommand: "test -d $HOME/.oh-my-zsh"

# Check if rustup is installed and functional
probeCommand: "command -v rustup && rustup --version"

# Check if a specific font is installed
probeCommand: "fc-list | grep -qi 'JetBrainsMono'"

# Check if tmux plugin manager is present
probeCommand: "test -d $HOME/.tmux/plugins/tpm"
```

### 6.2 `compiled-binary` module — `versionCommand` field

```yaml
- type: compiled-binary
  name: install-neovim
  url: https://github.com/neovim/neovim/releases/latest/download/nvim-linux-x86_64.tar.gz
  installPath: /usr/local/bin/nvim
  versionCommand: "nvim --version"    # used by probe to detect installed version
  expectedVersion: "0.10"             # if present: skip only if version starts with this prefix
  checksum:
    algorithm: sha256
    value: abc123...
```

If `expectedVersion` is declared, the probe will:
- Run `versionCommand`.
- If the detected version starts with `expectedVersion` → skip.
- If the version differs → **do not skip** (upgrade scenario).

This gives you upgrade-awareness for compiled binaries.

### 6.3 `packages` module — no changes needed

Package probes (`rpm -q`, `pacman -Q`, etc.) are always available — no extra YAML config needed for packages.

---

## 7. Integration with `BootstrapOrchestratorImpl`

The orchestrator gains `SkipEvaluator` as a new injected dependency:

```java
public final class BootstrapOrchestratorImpl implements BootstrapOrchestrator {

    private final PackageManagerExecutorRegistry executorRegistry;
    private final ShellScriptExecutor shellScriptExecutor;
    private final CompiledBinaryInstaller binaryInstaller;
    private final FlatpakInstaller flatpakInstaller;
    private final SkipEvaluator skipEvaluator;
    private final StateRepository stateRepository;

    // ... constructor ...

    private StepResult executePackageWithSkipCheck(
        PackageName pkg,
        ModuleName moduleName,
        PackageManagerKind manager,
        ExecutionEventListener listener
    ) {
        var decision = skipEvaluator.evaluate(pkg.value(), ItemType.PACKAGE);

        return switch (decision) {
            case SkipDecision.Skip s -> {
                var result = new StepResult.Skipped(pkg.value(), describeSkipReason(s.reason()));
                listener.onEvent(new ExecutionEvent(
                    moduleName, pkg.value(), EventKind.ITEM_COMPLETED, result, Instant.now()));
                yield result;
            }
            case SkipDecision.Run r -> {
                listener.onEvent(new ExecutionEvent(
                    moduleName, pkg.value(), EventKind.ITEM_STARTED, null, Instant.now()));
                var executor = executorRegistry.forKind(manager);
                var result = executor.install(pkg);
                if (result instanceof StepResult.Success success) {
                    stateRepository.recordSuccess(moduleName.value(), new StateEntry(
                        /* profileName */ currentProfile,
                        moduleName.value(),
                        pkg.value(),
                        ItemType.PACKAGE,
                        success.completedAt(),
                        success.version(),
                        null
                    ));
                }
                listener.onEvent(new ExecutionEvent(
                    moduleName, pkg.value(), EventKind.ITEM_COMPLETED, result, Instant.now()));
                yield result;
            }
        };
    }

    private String describeSkipReason(InstallationStatus status) {
        return switch (status) {
            case InstallationStatus.InstalledFromState s ->
                "already installed (recorded %s)".formatted(formatInstant(s.installedAt()));
            case InstallationStatus.InstalledByProbe p ->
                "already installed (version: %s)".formatted(
                    p.detectedVersion() != null ? p.detectedVersion() : "unknown");
            default -> "already installed";
        };
    }
}
```

**State recording rules:**
- Record success **immediately** after each successful item — never batch at module end.
- Do NOT record failures in the success state (failures remain "not installed" for next run).
- Do NOT record `Skipped` items — they are already in state from a previous run.
- The state write must be atomic (§5.2) — a crash between two package installs must not corrupt state.

---

## 8. New CLI Flags

### 8.1 On `RunCommand`

```java
@Option(
    names = {"--skip-already-installed"},
    description = {
        "Skip items that are already installed.",
        "Checks the state file (~/.local/share/sysboot/<profile>.state.json) first,",
        "then falls back to a live OS probe for items not in state.",
        "Default: false (always run everything)."
    }
)
private boolean skipAlreadyInstalled;

@Option(
    names = {"--re-probe"},
    description = {
        "When combined with --skip-already-installed: ignore the state file",
        "and always run a live OS probe for every item.",
        "Useful if the state file may be stale (e.g. after manual uninstall).",
        "Default: false."
    }
)
private boolean reProbe;

@Option(
    names = {"--probe-only"},
    description = {
        "Only run probes; do not install anything.",
        "Prints a report of what is installed, what is missing, and what would be skipped.",
        "Implies --skip-already-installed and --dry-run."
    }
)
private boolean probeOnly;
```

### 8.2 New subcommand: `sysboot status`

```
sysboot status [-c CONFIG] [--profile PROFILE]

Shows the current installation status of every item in a profile.
Reads the state file and runs live probes. Does not install anything.

Output columns:
  MODULE       ITEM                  TYPE      STATUS              VERSION        LAST INSTALLED
  ──────────────────────────────────────────────────────────────────────────────────────────────
  core-cli     git                   package   ✓ installed         2.45.2         2026-06-14
  core-cli     curl                  package   ✓ installed         8.6.0          2026-06-14
  core-cli     htop                  package   ✗ not installed     —              —
  desktop      com.spotify.Client    flatpak   ✓ installed         —              2026-06-14
  install-nvim /usr/local/bin/nvim   binary    ✓ installed         0.10.1         2026-06-14
  install-sdk  scripts/cli-tools.sh  script    ✓ from state        —              2026-06-14
```

Source of truth for each row:
1. State file → "✓ from state (verified)" if also confirmed by probe, "✓ from state" if probe not run.
2. Live probe (no state) → "✓ installed (probed)".
3. Neither → "✗ not installed".

### 8.3 New subcommand: `sysboot state`

```
sysboot state <subcommand>

Subcommands:
  show     Print the raw state file as formatted JSON
  reset    Delete the state file for a profile (will re-probe/re-install on next run)
  forget   Remove specific items from the state file
           e.g.: sysboot state forget --profile fedora-workstation --item git,curl

Examples:
  sysboot state show
  sysboot state show --profile fedora-workstation
  sysboot state reset --profile fedora-workstation
  sysboot state forget --profile fedora-workstation --item htop
```

---

## 9. TUI Changes for Idempotency UX

### 9.1 Skip display in `ExecutionScreen`

Skipped items must be visually distinct from both "not yet run" (pending) and "failed":

```
  PACKAGE               STATUS                  DURATION
  git                   ✓ installed (state)      —
  curl                  ✓ installed (state)      —
  wget                  ○ skip (probed v8.6.0)   —
  htop                  ⟳ installing...          1.2s
  ripgrep               ◌ pending
```

Color scheme additions:
- `Color.CYAN` — skipped via state file (`✓ state`)
- `Color.BLUE` — skipped via live probe (`○ probe`)
- `Color.YELLOW` — running (`⟳`)
- `Color.GREEN` — success (`✓`)
- `Color.RED` — failed (`✗`)
- `Color.WHITE` / dim — pending (`◌`)

### 9.2 Pre-execution probe phase

When `--skip-already-installed` is active, before execution begins:
1. Show a brief "Checking installation status..." phase in the TUI.
2. Run all probes in parallel (virtual threads, one per item).
3. Show a summary panel: "X items will be installed, Y items will be skipped".
4. Ask user to confirm: `[Enter] Proceed  [q] Quit  [d] Details`.
5. "Details" shows the full status table (same as `sysboot status`).

```
┌─ sysboot ── fedora-workstation ────────────────────────────────────────┐
│                                                                        │
│   🔍 Checking installation status...                                   │
│                                                                        │
│   [████████████████████░░░░░░░░░░░░░░░░] 57%                          │
│   Probing: ripgrep                                                     │
│                                                                        │
│   ✓ Already installed:  14 items (will be skipped)                    │
│   ◌ Not installed:       8 items (will be installed)                  │
│   ? Unknown:             2 items (will attempt installation)           │
│                                                                        │
│   [Enter] Proceed   [d] Show details   [q] Quit                       │
└────────────────────────────────────────────────────────────────────────┘
```

### 9.3 Post-execution summary screen

After execution completes, show a summary:

```
┌─ sysboot ── Run Complete ───────────────────────────────────────────────┐
│                                                                         │
│   Profile:  fedora-workstation     Duration: 4m 23s                    │
│                                                                         │
│   ✓ Installed:   8   ○ Skipped: 14   ✗ Failed: 1   Total: 23          │
│                                                                         │
│   Failed items:                                                         │
│     ✗ wget  [dnf]  Package not found in repositories                   │
│                                                                         │
│   State file: ~/.local/share/sysboot/fedora-workstation.state.json     │
│   Updated: 8 new entries recorded                                      │
│                                                                         │
│   [r] Retry failed   [s] Show full log   [q] Quit                      │
└─────────────────────────────────────────────────────────────────────────┘
```

### 9.4 "Retry failed" UX

`[r]` in the summary screen re-runs the orchestrator with only the items that returned `StepResult.Failure` in the current session. The state file is not involved — this is a session-level retry list held in memory. Retried items, if successful, ARE recorded to the state file.

---

## 10. `--probe-only` Mode Report (stdout + TUI table)

When `--probe-only` is passed, `sysboot` runs all probes, prints the status table (same as `sysboot status`), and exits without installing anything. Exit code:
- `0` — all items confirmed installed.
- `1` — one or more items are not installed.
- `2` — one or more probes returned `Unknown`.

This allows use in shell scripts:
```bash
sysboot probe-only -c fedora-workstation.yaml && echo "System is fully bootstrapped"
```

---

## 11. Parallel Probing Architecture

Probes run in parallel using virtual threads. The probe phase is read-only so concurrency is safe.

```java
public final class ParallelProbeRunner {

    private final InstalledProbeRegistry probeRegistry;

    public Map<String, InstallationStatus> probeAll(
        List<BootstrapModule> modules,
        Consumer<String> progressCallback  // called with item name as each probe completes
    ) {
        var results = new ConcurrentHashMap<String, InstallationStatus>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = collectProbeTargets(modules).stream()
                .map(target -> executor.submit(() -> {
                    var status = probeRegistry.probe(target.itemKey(), target.itemType());
                    results.put(target.itemKey(), status);
                    progressCallback.accept(target.itemKey());
                    return status;
                }))
                .toList();

            // Wait for all probes with a global timeout
            for (var future : futures) {
                try {
                    future.get(60, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    // Individual probe timeout — already handled inside probe impls
                }
            }
        }

        return Collections.unmodifiableMap(results);
    }
}
```

---

## 12. Updated Domain Tests (additions to Phase 9)

New unit tests required:

| Test class | Scenarios |
|---|---|
| `DnfPackageProbeTest` | rpm exits 0 → InstalledByProbe; exits 1 → NotInstalled; exits 2 → Unknown |
| `AptPackageProbeTest` | dpkg output "install ok installed" → InstalledByProbe; other → NotInstalled |
| `FlatpakProbeTest` | App ID in list → InstalledByProbe; not in list → NotInstalled; flatpak not found → Unknown |
| `CompiledBinaryProbeTest` | File exists + executable → InstalledByProbe; not exists → NotInstalled; exists not executable → Unknown |
| `ShellScriptProbeTest` | probeCommand exits 0 → InstalledByProbe; non-zero → NotInstalled; no probeCommand → Unknown |
| `SkipEvaluatorTest` | skipAlreadyInstalled=false → always Run; item in state → Skip(FromState); not in state + probe installed → Skip(ByProbe); not in state + probe not installed → Run; not in state + probe unknown → Run (fail-safe) |
| `SkipEvaluatorTest` | --re-probe: ignores state file, goes straight to probe |
| `JsonStateRepositoryTest` | Load absent file → Optional.empty(); load valid file → BootstrapState; load corrupt file → Optional.empty() + warning (no throw); recordSuccess idempotent (second call replaces first); atomic write (no partial state on simulated crash) |
| `ParallelProbeRunnerTest` | All probes run; results keyed by itemKey; progress callback called once per item |

---

## 13. Updated Config Examples

Add `probeCommand` to shell-script entries in all three example configs:

```yaml
# config/example-fedora.yaml additions

  - type: shell-script
    name: install-sdkman
    script: scripts/cli-tools.sh
    args: ["--sdkman"]
    probeCommand: "test -d $HOME/.sdkman"
    continueOnError: false

  - type: shell-script
    name: install-oh-my-zsh
    script: scripts/cli-tools.sh
    args: ["--omz"]
    probeCommand: "test -d $HOME/.oh-my-zsh"
    continueOnError: false

  - type: shell-script
    name: install-tpm
    script: scripts/cli-tools.sh
    args: ["--tpm"]
    probeCommand: "test -d $HOME/.tmux/plugins/tpm"
    continueOnError: true

  - type: compiled-binary
    name: install-neovim
    url: https://github.com/neovim/neovim/releases/download/v0.10.1/nvim-linux-x86_64.tar.gz
    installPath: /usr/local/bin/nvim
    versionCommand: "nvim --version"
    expectedVersion: "0.10"
    checksum:
      algorithm: sha256
      value: "ba2bcc1f2e0af2028be06da3f3f1a23f"
```

---

## 14. Hard Constraints (additions)

- [ ] State file writes are always atomic (temp file + rename).
- [ ] A corrupt or missing state file never causes a run failure — degrade gracefully.
- [ ] Probe commands run with a **10-second timeout** per item (package managers), **30-second timeout** for shell probes.
- [ ] `--probe-only` makes zero writes — no state file updates, no installs.
- [ ] `Unknown` probe result always resolves to `Run` (fail-safe) — never silently skip.
- [ ] Parallel probe phase uses virtual threads; probe thread count is unbounded (let the JVM schedule).
- [ ] Passwords (`char[]`) are never written to the state file under any circumstances.
- [ ] `sysboot state reset` prompts for confirmation before deleting the state file.
- [ ] `StepResult.Skipped` items are not added to the state file.
- [ ] The version captured in the state file is the version **actually installed**, not the version declared in config.


# Addendum: Execution Model, Phases & Ordering, CLI/TUI Duality, New Module Types

> This document extends both `AGENT-PROMPT-sysboot.md` and
> `ADDENDUM-idempotency-and-state.md`. All prior rules remain in force.
> When a rule here conflicts with a prior document, this document wins.

---

## 1. The Core Problem: Ordered Execution with Hard Dependencies

A fresh-OS bootstrap is **not** a flat list of independent tasks. It is a
**directed acyclic graph (DAG) of phases**, where some nodes are *hard
prerequisites* for others, and some require environment changes (new `$PATH`
entries, shell restarts, sourced init files) that the parent `sysboot` process
cannot observe.

Examples of hard constraints:

```
install zsh
    ↓ (zsh must exist before this)
chsh -s $(which zsh)          ← change default shell
    ↓ (shell change must precede oh-my-zsh install;
        oh-my-zsh installer itself launches a new zsh)
install oh-my-zsh
    ↓
install zsh plugins
    ↓ (plugins must exist before .zshrc is sourced)
source ~/.zshrc               ← this CANNOT happen in the sysboot process itself
    ↓
install rustup                ← rustup modifies PATH; sourcing .cargo/env needed
    ↓
cargo install ...             ← requires rustup's PATH
    ↓
install juliaup
install sdkman
    ↓
sdk install java ...
```

The design must encode this graph explicitly in config, not rely on a flat
ordered list.

---

## 2. New Domain Concepts

### 2.1 `Phase` — a named, ordered group of modules

```java
public record Phase(
    PhaseName name,
    String description,
    List<BootstrapModule> modules,
    List<PhaseName> dependsOn,          // phases that must complete before this one
    RestartPolicy restartPolicy,
    boolean continueOnModuleError       // if false: abort the whole phase on first module failure
) {}

public record PhaseName(String value) {
    public PhaseName { Objects.requireNonNull(value); if (value.isBlank()) throw new IllegalArgumentException(); }
}
```

### 2.2 `RestartPolicy` — what to do after a phase completes

```java
public sealed interface RestartPolicy permits
    RestartPolicy.None,
    RestartPolicy.PromptRestart,
    RestartPolicy.RequiresNewShell {}

public sealed interface RestartPolicy {
    /** No special action needed after this phase. */
    record None() implements RestartPolicy {}

    /**
     * Sysboot will pause and display a message asking the user to log out
     * and back in (e.g. after chsh or docker group membership changes).
     * The user must re-run sysboot to continue. State file records the
     * completed phase so it is skipped on next run.
     */
    record PromptRestart(String message) implements RestartPolicy {}

    /**
     * The next phase must run in a new shell environment to pick up PATH
     * changes (e.g. after rustup, sdkman, oh-my-zsh modify ~/.zshrc).
     * Sysboot spawns subsequent commands via 'zsh -i -c ...' or
     * 'bash -i -c ...' to source the user's shell init files.
     * No session restart needed — just a new subprocess with a login shell.
     */
    record RequiresNewShell(ShellKind shell) implements RestartPolicy {}
}

public enum ShellKind { ZSH, BASH, SH }
```

### 2.3 New `BootstrapModule` subtypes (additions to sealed hierarchy)

```java
public sealed interface BootstrapModule permits
    PackageModule,
    FlatpakModule,
    ShellScriptModule,
    CompiledBinaryModule,
    ZypperModule,
    DotbotModule,           // NEW: runs dotbot-go for dotfile linking
    DefaultShellModule,     // NEW: chsh -s <shell>
    OhMyZshModule,          // NEW: installs oh-my-zsh (specific enough to warrant own type)
    ToolchainModule,        // NEW: rustup, juliaup, sdkman, etc.
    NerdFontModule,         // NEW: runs nerdfont-install
    ShellReloadModule       // NEW: sources shell init in a new subprocess (not a real reload)
{}
```

---

## 3. Updated YAML Config Schema

### 3.1 Top-level structure

```yaml
profile: fedora-workstation
os:
  type: fedora
  release: "41"

# Phases are executed in dependency order, not list order.
# Dependency resolution determines actual execution sequence.
phases:
  - name: dotfiles
    description: "Link dotfiles via dotbot-go"
    dependsOn: []
    restartPolicy:
      type: none
    modules:
      - type: dotbot
        name: link-dotfiles
        config: ~/.dotfiles/install.conf.yaml
        dotbotBinary: dotbot   # must be on PATH or absolute

  - name: shell-foundation
    description: "Install zsh + tmux, set default shell"
    dependsOn: [dotfiles]
    restartPolicy:
      type: prompt-restart
      message: |
        Your default shell has been changed to zsh.
        Please log out and log back in, then re-run:
          sysboot run -c ~/.config/sysboot/fedora-workstation.yaml --skip-already-installed
    modules:
      - type: packages
        name: shell-packages
        packageManager: dnf
        continueOnError: false   # zsh MUST succeed; tmux MUST succeed
        packages:
          - zsh
          - tmux

      - type: default-shell
        name: set-zsh-default
        shell: /bin/zsh
        probeCommand: "[ \"$SHELL\" = '/bin/zsh' ] || getent passwd $USER | cut -d: -f7 | grep -q zsh"

  - name: zsh-ecosystem
    description: "Oh-My-Zsh, plugins, zsh config"
    dependsOn: [shell-foundation]
    restartPolicy:
      type: requires-new-shell
      shell: zsh
    modules:
      - type: oh-my-zsh
        name: install-ohmyzsh
        installDir: ~/.oh-my-zsh
        customPluginsDir: ~/.oh-my-zsh/custom/plugins
        theme: powerlevel10k/powerlevel10k
        probeCommand: "test -d ~/.oh-my-zsh"

      - type: shell-script
        name: install-zsh-plugins
        script: scripts/install-zsh-plugins.sh
        probeCommand: |
          test -d ~/.oh-my-zsh/custom/plugins/zsh-syntax-highlighting &&
          test -d ~/.oh-my-zsh/custom/plugins/zsh-autosuggestions

      - type: shell-reload
        name: reload-zshrc
        description: "Source ~/.zshrc in a new zsh subprocess to validate setup"
        shell: zsh

  - name: toolchains
    description: "Language toolchains: rustup, juliaup, sdkman"
    dependsOn: [zsh-ecosystem]
    restartPolicy:
      type: requires-new-shell
      shell: zsh
    modules:
      - type: toolchain
        name: install-rustup
        kind: rustup
        probeCommand: "command -v rustup"
        installScript: "curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --no-modify-path"
        postInstallEnvSource: "$HOME/.cargo/env"

      - type: toolchain
        name: install-juliaup
        kind: juliaup
        probeCommand: "command -v juliaup"
        installScript: "curl -fsSL https://install.julialang.org | sh -s -- -y"
        postInstallEnvSource: "$HOME/.juliaup/bin"  # directory to add to PATH

      - type: toolchain
        name: install-sdkman
        kind: sdkman
        probeCommand: "test -d ~/.sdkman && command -v sdk"
        installScript: "curl -s 'https://get.sdkman.io' | bash"
        postInstallEnvSource: "$HOME/.sdkman/bin/sdkman-init.sh"

  - name: cli-packages
    description: "DNF packages: CLI tools, dev tools"
    dependsOn: [shell-foundation]
    restartPolicy:
      type: none
    modules:
      - type: packages
        name: core-cli-tools
        packageManager: dnf
        continueOnError: true
        packages:
          - git
          - curl
          - wget
          - htop
          - ripgrep
          - fd-find
          - bat
          - eza
          - fzf
          - jq
          - yq
          - zellij
          - neovim
          - lazygit
          - just
          - bottom
          - tealdeer

      - type: packages
        name: dev-tools
        packageManager: dnf
        continueOnError: true
        packages:
          - gcc
          - clang
          - cmake
          - make
          - pkg-config
          - openssl-devel
          - go
          - nodejs
          - python3
          - python3-pip

  - name: fonts
    description: "Nerd Fonts"
    dependsOn: [cli-packages]
    restartPolicy:
      type: none
    modules:
      - type: nerd-fonts
        name: install-nerd-fonts
        config:
          release: latest
          destination: ~/.local/share/fonts/NerdFonts
          refreshFontCache: true
          families:
            - JetBrainsMono
            - Hack
            - FiraCode
            - Meslo
            - SymbolsOnly
        nerdfontBinary: nerdfont-install   # must be on PATH or absolute path
        probeCommand: "fc-list | grep -qi 'JetBrainsMono'"

  - name: flatpaks
    description: "Flatpak desktop apps"
    dependsOn: [cli-packages]
    restartPolicy:
      type: none
    modules:
      - type: flatpak
        name: desktop-apps
        remote: flathub
        continueOnError: true
        appIds:
          - com.spotify.Client
          - org.telegram.desktop
          - com.obsproject.Studio
          - org.gimp.GIMP

  - name: binaries
    description: "Compiled binaries: neovim, yazi, etc."
    dependsOn: [cli-packages]
    restartPolicy:
      type: none
    modules:
      - type: compiled-binary
        name: install-neovim
        binaryName: nvim
        url: https://github.com/neovim/neovim/releases/download/v0.10.1/nvim-linux-x86_64.tar.gz
        installPath: ~/.local/bin/nvim
        versionCommand: "nvim --version"
        expectedVersion: "0.10"
        checksum:
          algorithm: sha256
          value: "abc123..."
```

### 3.2 Dependency resolution rules

- The executor resolves the DAG via topological sort (Kahn's algorithm).
- Circular dependencies are detected at `validate` time and reported as an error.
- Phases with no unmet dependencies may run in parallel (controlled by `--parallel-phases` flag, default: false for safety).
- A phase that fails (any module fails with `continueOnError: false`, or the phase's own `continueOnModuleError: false`) marks all dependent phases as `Blocked` and skips them.
- State file records completed **phases** in addition to individual items.

---

## 4. New Module Type Implementations (`executor` module)

### 4.1 `DotbotExecutor`

Runs `dotbot-go` (or original `dotbot`) as an external process.

```java
public final class DotbotExecutor implements ModuleExecutor<DotbotModule> {

    private final ShellRunner shellRunner;
    private final SudoPasswordProvider sudoPasswordProvider;

    @Override
    public Class<DotbotModule> moduleType() { return DotbotModule.class; }

    @Override
    public StepResult execute(DotbotModule module, ExecutionEventListener listener) {
        // Resolve dotbot binary — check module config, then PATH
        var binary = resolveBinary(module.dotbotBinary());

        var command = List.of(
            binary,
            "--config", module.config().toString()
        );

        // dotbot-go does not need sudo; it links files in the user's home
        var result = shellRunner.run(command, Map.of(), Duration.ofMinutes(5));

        return result.exitCode() == 0
            ? new StepResult.Success("dotbot", detectVersion(binary), Duration.ZERO)
            : new StepResult.Failure("dotbot", result.stderr(), result.exitCode(), Duration.ZERO);
    }
}
```

**Probe**: `DotbotProbe` checks whether the declared symlinks in the dotbot config already exist and point to the right targets. Since dotbot-go is idempotent by design, re-running it is always safe — but we still check the state file first.

### 4.2 `DefaultShellExecutor`

Changes the default shell using `chsh`:

```java
public final class DefaultShellExecutor implements ModuleExecutor<DefaultShellModule> {

    private final PtyShellRunner ptyShellRunner;  // chsh prompts for password on some distros

    @Override
    public StepResult execute(DefaultShellModule module, ExecutionEventListener listener) {
        // Verify the shell binary exists first
        if (!Files.exists(module.shellPath())) {
            return new StepResult.Failure(
                "default-shell",
                "Shell binary not found: " + module.shellPath(),
                1,
                Duration.ZERO
            );
        }

        // chsh may ask for password — use PTY runner
        var result = ptyShellRunner.run(
            List.of("chsh", "-s", module.shellPath().toString()),
            Map.of(),
            Duration.ofSeconds(30)
        );

        return result.exitCode() == 0
            ? new StepResult.Success("default-shell", null, Duration.ZERO)
            : new StepResult.Failure("default-shell", result.stderr(), result.exitCode(), Duration.ZERO);
    }
}
```

**Probe** (`DefaultShellProbe`):
```java
// Check /etc/passwd entry for current user — does not require a new shell
var result = shellRunner.run(
    List.of("getent", "passwd", System.getProperty("user.name")),
    Map.of(), Duration.ofSeconds(5)
);
// "/etc/passwd line: user:x:1000:1000::/home/user:/bin/zsh"
// Split by ':' and check field 6
```

### 4.3 `OhMyZshExecutor`

Oh-My-Zsh installation is special: its installer script itself **launches an interactive zsh session**. The standard curl-pipe-sh pattern must be handled carefully:

```java
public final class OhMyZshExecutor implements ModuleExecutor<OhMyZshModule> {

    private final PtyShellRunner ptyShellRunner;

    @Override
    public StepResult execute(OhMyZshModule module, ExecutionEventListener listener) {
        // The official OMZ installer exits the current shell after installing.
        // Use RUNZSH=no to prevent it from launching a new shell.
        // Use CHSH=no because we handle chsh separately in DefaultShellModule.
        var env = Map.of(
            "RUNZSH", "no",
            "CHSH", "no",
            "HOME", System.getProperty("user.home")
        );

        // Download installer to a temp file first — do NOT pipe curl directly to sh
        // (security: verify we got what we expected before executing)
        var installerPath = downloadToTemp(
            "https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh"
        );

        var result = ptyShellRunner.run(
            List.of("sh", installerPath.toString()),
            env,
            Duration.ofMinutes(5)
        );

        Files.deleteIfExists(installerPath);

        return result.exitCode() == 0
            ? new StepResult.Success("oh-my-zsh", null, Duration.ZERO)
            : new StepResult.Failure("oh-my-zsh", result.stderr(), result.exitCode(), Duration.ZERO);
    }
}
```

**Critical**: `RUNZSH=no` and `CHSH=no` env vars are mandatory. Without them, the OMZ installer would try to exec a new zsh and exit, which would make `sysboot` lose control of the process.

### 4.4 `ToolchainExecutor`

Handles rustup, juliaup, sdkman, and future toolchains generically:

```java
public final class ToolchainExecutor implements ModuleExecutor<ToolchainModule> {

    private final PtyShellRunner ptyShellRunner;
    private final ShellRunner shellRunner;

    @Override
    public StepResult execute(ToolchainModule module, ExecutionEventListener listener) {
        // 1. Download install script to temp file
        var scriptPath = downloadToTemp(module.installScriptUrl());

        // 2. Execute it non-interactively
        //    Most toolchain installers (rustup, juliaup) support -y / --no-modify-profile
        //    We set CARGO_HOME, RUSTUP_HOME explicitly to avoid $PATH pollution issues
        var env = buildEnvFor(module);

        var result = ptyShellRunner.run(
            List.of("sh", scriptPath.toString()),
            env,
            Duration.ofMinutes(15)
        );

        Files.deleteIfExists(scriptPath);

        if (result.exitCode() != 0) {
            return new StepResult.Failure(module.name(), result.stderr(), result.exitCode(), Duration.ZERO);
        }

        // 3. Detect installed version
        var version = tryDetectVersion(module);

        return new StepResult.Success(module.name(), version, Duration.ZERO);
    }

    private Map<String, String> buildEnvFor(ToolchainModule module) {
        // For rustup: RUSTUP_INIT_SKIP_PATH_CHECK=yes prevents it from complaining
        // that .cargo/bin is not on PATH yet (it won't be until next shell)
        return switch (module.kind()) {
            case RUSTUP -> Map.of(
                "RUSTUP_INIT_SKIP_PATH_CHECK", "yes",
                "CARGO_HOME", Path.of(System.getProperty("user.home"), ".cargo").toString(),
                "RUSTUP_HOME", Path.of(System.getProperty("user.home"), ".rustup").toString()
            );
            case SDKMAN -> Map.of(
                "SDKMAN_DIR", Path.of(System.getProperty("user.home"), ".sdkman").toString()
            );
            default -> Map.of();
        };
    }
}
```

### 4.5 `NerdFontExecutor`

Delegates to the `nerdfont-install` binary (Go binary, separate from sysboot):

```java
public final class NerdFontExecutor implements ModuleExecutor<NerdFontModule> {

    private final ShellRunner shellRunner;

    @Override
    public StepResult execute(NerdFontModule module, ExecutionEventListener listener) {
        // Write a temporary nerdfont-install config YAML
        var tempConfig = writeTempNerdFontConfig(module.config());

        var binary = resolveBinary(module.nerdfontBinary());
        var command = List.of(binary, "--config", tempConfig.toString());

        var result = shellRunner.run(command, Map.of(), Duration.ofMinutes(15));
        Files.deleteIfExists(tempConfig);

        return result.exitCode() == 0
            ? new StepResult.Success("nerd-fonts", null, Duration.ZERO)
            : new StepResult.Failure("nerd-fonts", result.stderr(), result.exitCode(), Duration.ZERO);
    }

    private Path writeTempNerdFontConfig(NerdFontConfig config) {
        // Serialize config to YAML in a temp file
        // nerdfont-install reads it via --config flag
        var tempFile = Files.createTempFile("sysboot-nerdfonts-", ".yaml");
        objectMapper.writeValue(tempFile.toFile(), NerdFontConfigDto.from(config));
        return tempFile;
    }
}
```

**Probe** (`NerdFontProbe`): runs `fc-list | grep -qi '<FamilyName>'` for each declared font family. Skips if all families found.

### 4.6 `ShellReloadExecutor`

This does NOT actually reload the current sysboot process's shell. It spawns a
new interactive login shell subprocess, sources init files, and validates that
the environment is sane. Its purpose is twofold:

1. Validate that the shell config installed correctly (e.g. `.zshrc` sources without errors).
2. Enable subsequent phases to run commands in a new-shell context via `RequiresNewShell`.

```java
public final class ShellReloadExecutor implements ModuleExecutor<ShellReloadModule> {

    private final PtyShellRunner ptyShellRunner;

    @Override
    public StepResult execute(ShellReloadModule module, ExecutionEventListener listener) {
        // Spawn an interactive login shell that sources init files, then exits cleanly.
        // The exit code tells us whether init files loaded without errors.
        var shellBinary = switch (module.shell()) {
            case ZSH -> "zsh";
            case BASH -> "bash";
            case SH -> "sh";
        };

        // '--login' sources /etc/profile and ~/.zprofile / ~/.zshrc
        // '-i' makes it interactive (required for .zshrc sourcing in zsh)
        // '-c exit' causes it to exit immediately after sourcing
        var result = ptyShellRunner.run(
            List.of(shellBinary, "--login", "-i", "-c", "echo 'Shell environment OK'; exit 0"),
            Map.of(),
            Duration.ofSeconds(30)
        );

        return result.exitCode() == 0
            ? new StepResult.Success("shell-reload", null, Duration.ZERO)
            : new StepResult.Failure(
                "shell-reload",
                "Shell init failed. Check your .zshrc / .bashrc for errors.\n" + result.stderr(),
                result.exitCode(),
                Duration.ZERO
            );
    }
}
```

### 4.7 The `RequiresNewShell` execution wrapper

When a phase declares `restartPolicy: requires-new-shell`, the executor wraps
all subsequent shell commands in that phase inside an interactive login shell:

```java
/**
 * Wraps a ShellRunner so that every command is executed inside a new
 * interactive login shell subprocess, picking up PATH changes from
 * ~/.zshrc, ~/.cargo/env, ~/.sdkman/bin/sdkman-init.sh, etc.
 *
 * Example: instead of running ["rustup", "show"],
 * it runs ["zsh", "--login", "-i", "-c", "rustup show"].
 */
public final class LoginShellWrappingRunner implements ShellRunner {

    private final ShellRunner delegate;
    private final ShellKind shell;

    @Override
    public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
        var shellBinary = switch (shell) {
            case ZSH -> "zsh";
            case BASH -> "bash";
            case SH -> "sh";
        };

        // Join command into a single string for the shell's -c argument
        var commandString = command.stream()
            .map(this::quoteIfNeeded)
            .collect(Collectors.joining(" "));

        var wrappedCommand = List.of(shellBinary, "--login", "-i", "-c", commandString);
        return delegate.run(wrappedCommand, env, timeout);
    }

    private String quoteIfNeeded(String arg) {
        return arg.contains(" ") ? "'" + arg.replace("'", "'\\''") + "'" : arg;
    }
}
```

The orchestrator selects the appropriate `ShellRunner` per phase based on the
active `RestartPolicy`:
- `None` / `PromptRestart` → `DefaultShellRunner`
- `RequiresNewShell(zsh)` → `LoginShellWrappingRunner(delegate=DefaultShellRunner, shell=ZSH)`

---

## 5. Phase Execution in the Orchestrator

### 5.1 DAG resolution

```java
public final class PhaseExecutionPlanner {

    /**
     * Performs a topological sort of phases using Kahn's algorithm.
     * Returns phases in execution order.
     * Throws ConfigurationException if a cycle is detected.
     */
    public List<Phase> plan(List<Phase> phases) {
        // Standard Kahn's algorithm implementation
        // — builds adjacency list from dependsOn
        // — processes nodes with in-degree 0
        // — detects cycles by checking remaining nodes after BFS
    }

    /**
     * Returns the set of phases blocked because their dependency failed.
     */
    public Set<PhaseName> computeBlocked(
        List<Phase> allPhases,
        Set<PhaseName> failedPhases
    ) {
        // BFS/DFS: any phase whose transitive dependency is in failedPhases is blocked
    }
}
```

### 5.2 Phase state in the state file

Add to `BootstrapState`:

```java
public record BootstrapState(
    String profileName,
    Instant lastRunAt,
    String sysbootVersion,
    List<StateEntry> entries,
    List<PhaseStateEntry> phaseEntries   // NEW
) {}

public record PhaseStateEntry(
    String phaseName,
    PhaseStatus status,
    Instant completedAt
) {}

public enum PhaseStatus { COMPLETED, FAILED, BLOCKED, SKIPPED }
```

With `--skip-already-installed`, an entire phase is skipped if:
1. Its `PhaseStateEntry` has `status = COMPLETED` in the state file, AND
2. `--re-probe` is not set.

This enables the most important UX scenario: re-running sysboot after a prompted
logout/login skips phases 1–3 (already done) and continues from phase 4.

---

## 6. CLI/TUI Duality — Clarified Design

### 6.1 The two modes are equal citizens, not a fallback

Both modes use **identical domain logic, identical orchestrator, identical state
file**. The only difference is the `ExecutionEventListener` implementation and
the `SudoPasswordProvider` implementation.

```
┌──────────────────────────────────────────────────────────────┐
│                   sysboot process                            │
│                                                              │
│  ┌────────────┐        ┌──────────────────────────────────┐  │
│  │ PicoCLI    │        │ ApplicationContext (wiring)       │  │
│  │ Command    │──────▶ │  - BootstrapOrchestrator         │  │
│  │ parsing    │        │  - ConfigLoader                  │  │
│  └─────┬──────┘        │  - StateRepository               │  │
│        │               │  - SkipEvaluator                 │  │
│        │ selects       │  - PhaseExecutionPlanner         │  │
│        ▼               └──────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐    │
│  │              OutputAdapter (sealed)                  │    │
│  │                                                      │    │
│  │  TuiOutputAdapter          PlainOutputAdapter        │    │
│  │  ├─ TamboUI TuiRunner      ├─ StdoutEventListener    │    │
│  │  ├─ TuiEventListener       ├─ StdinPasswordProvider  │    │
│  │  └─ TuiPasswordProvider    └─ (ANSI color via        │    │
│  │     (modal overlay)            picocli Help.Ansi)    │    │
│  └──────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────┘
```

### 6.2 `OutputAdapter` sealed interface

```java
public sealed interface OutputAdapter permits TuiOutputAdapter, PlainOutputAdapter {
    ExecutionEventListener eventListener();
    SudoPasswordProvider sudoPasswordProvider();
    void start();    // called before orchestrator begins
    void stop();     // called after orchestrator finishes
}
```

`ApplicationContext` receives an `OutputAdapter` and injects its two components
into the orchestrator. The CLI layer decides which adapter to instantiate based
on whether `--no-tui` is set and whether stdout is a TTY:

```java
OutputAdapter adapter = (options.noTui || !System.console().isTerminal())
    ? new PlainOutputAdapter()
    : new TuiOutputAdapter();
```

### 6.3 `PlainOutputAdapter` — plain CLI behavior

**Sudo password in plain mode**: `StdinPasswordProvider` reads from stdin using
`Console.readPassword()` (masks input, works in a terminal):

```java
public final class StdinPasswordProvider implements SudoPasswordProvider {
    private final Console console = System.console();

    @Override
    public Optional<char[]> requestPassword(String prompt) {
        if (console == null) {
            // Not a real terminal — cannot prompt interactively.
            // Return empty to abort the step.
            return Optional.empty();
        }
        char[] password = console.readPassword("%s", prompt);
        return Optional.ofNullable(password);
    }
}
```

**Progress in plain mode**: `StdoutEventListener` uses ANSI escape codes via
picocli's `Help.Ansi`:

```
[sysboot] Phase: shell-foundation
[sysboot]   ✓ zsh            (dnf)       0.8s
[sysboot]   ✓ tmux           (dnf)       0.4s
[sysboot]   ○ set-zsh-default            already set
[sysboot] Phase complete: shell-foundation
[sysboot]
[sysboot] ⚠ Restart required:
[sysboot]   Your default shell has been changed to zsh.
[sysboot]   Please log out and log back in, then re-run:
[sysboot]     sysboot run -c ~/.config/sysboot/fedora-workstation.yaml --skip-already-installed
[sysboot]
[sysboot] Remaining phases (will run after restart):
[sysboot]   - zsh-ecosystem
[sysboot]   - toolchains
[sysboot]   - cli-packages
[sysboot]   - fonts
[sysboot]   - flatpaks
[sysboot]   - binaries
```

### 6.4 `TuiOutputAdapter` — TamboUI interactive behavior

Uses `TuiRunner` with `TuiCommand` (from `tamboui-picocli`).

**Phase view** (replaces flat item list): the TUI's execution screen is now
phase-aware. The left pane shows the phase DAG; the right pane shows the items
within the currently active phase.

```
┌─ sysboot ── fedora-workstation ─────────────────────────────────────────┐
│ PHASES                        │ PHASE: zsh-ecosystem                    │
│ ✓ dotfiles                    │                                         │
│ ✓ shell-foundation            │ MODULE              STATUS   DURATION   │
│ ⟳ zsh-ecosystem    ◀ active   │ install-ohmyzsh     ✓ done    12.3s    │
│ ○ toolchains                  │ install-zsh-plugins ⟳ running  2.1s    │
│ ○ cli-packages                │ reload-zshrc        ◌ pending           │
│ ○ fonts                       │                                         │
│ ○ flatpaks                    │ ─────────────────────────────────────── │
│ ○ binaries                    │ STDOUT (last 8 lines)                   │
│                               │ Cloning into 'zsh-syntax-highlight...' │
│                               │ remote: Counting objects: 147           │
│                               │ remote: Compressing objects: 100%       │
│                               │                                         │
├───────────────────────────────┴─────────────────────────────────────────┤
│ [q] Quit  [l] Full log  [p] Pause  [s] Skip phase  [Tab] Switch pane   │
└─────────────────────────────────────────────────────────────────────────┘
```

**Restart prompt in TUI mode** (for `PromptRestart` policy):

```
╔══════════════════════════════════════════════════════════════════╗
║  ⚠  Restart Required                                            ║
║                                                                  ║
║  Phase "shell-foundation" completed.                             ║
║                                                                  ║
║  Your default shell has been changed to zsh.                     ║
║  Please log out and log back in, then re-run:                    ║
║                                                                  ║
║    sysboot run -c ~/.config/sysboot/fedora.yaml \               ║
║               --skip-already-installed                           ║
║                                                                  ║
║  Progress saved. Completed phases will be skipped.              ║
║                                                                  ║
║  Remaining: zsh-ecosystem, toolchains, cli-packages, fonts      ║
║                                                                  ║
║  [Enter] Exit sysboot                                            ║
╚══════════════════════════════════════════════════════════════════╝
```

---

## 7. Complete CLI Command Reference (updated)

### 7.1 `sysboot run`

```
sysboot run [OPTIONS]

Options:
  -c, --config=FILE              Config file path
                                 [default: ~/.config/sysboot/default.yaml]
  --phase=NAME[,NAME...]         Run only these phases (comma-separated)
  --from-phase=NAME              Start from this phase (skip earlier phases,
                                 regardless of state — useful for debugging)
  --skip-already-installed       Skip items/phases recorded in state or
                                 confirmed installed by live probe
  --re-probe                     With --skip-already-installed: ignore state
                                 file, always live-probe
  --no-tui                       Plain stdout output (auto-detected if not a TTY)
  --dry-run                      Show what would happen; no changes made
  --parallel-phases              Run independent phases concurrently
                                 (default: false)
  -v, --verbose                  Include subprocess stdout in output

Examples:
  sysboot run -c fedora.yaml
  sysboot run -c fedora.yaml --skip-already-installed
  sysboot run -c fedora.yaml --phase cli-packages,fonts
  sysboot run -c fedora.yaml --from-phase toolchains
  sysboot run -c fedora.yaml --dry-run
  sysboot run -c fedora.yaml --no-tui --skip-already-installed
```

### 7.2 `sysboot status`

```
sysboot status [-c CONFIG] [--phase PHASE]

Shows installation status of every item in the profile.
Source: state file + live probes (read-only; no changes made).

Exit codes:
  0 — all items installed
  1 — one or more items not installed
  2 — one or more probe errors
```

### 7.3 `sysboot validate`

```
sysboot validate -c CONFIG

Validates the config file:
  - YAML parses correctly
  - All referenced scripts exist
  - Phase dependency graph has no cycles
  - All binary URLs are reachable (with --check-urls)
  - Package names are valid identifiers
  - All phase names in dependsOn refer to declared phases

Exit codes:
  0 — config is valid
  1 — validation errors found
```

### 7.4 `sysboot state`

```
sysboot state <subcommand>

Subcommands:
  show   [--profile NAME]            Print state file as formatted JSON
  reset  [--profile NAME] [--force]  Delete state file (prompts unless --force)
  forget --profile NAME --phase NAME Remove a phase entry (force re-run)
  forget --profile NAME --item KEY   Remove a specific item entry
  path   [--profile NAME]            Print path to state file
```

### 7.5 `sysboot plan`

```
sysboot plan -c CONFIG [--phase PHASE,...] [--skip-already-installed]

Prints the execution plan without running anything.
Shows: phase order, dependency graph, which items would be skipped/run.
Useful for understanding what a run will do before committing.

Output:
  Execution plan for: fedora-workstation

  Phase 1: dotfiles         [no deps]
    • link-dotfiles         dotbot    would run

  Phase 2: shell-foundation [after: dotfiles]
    • shell-packages        dnf       would install: zsh, tmux
    • set-zsh-default       chsh      ○ would skip (probe: already /bin/zsh)
    → After this phase: RESTART REQUIRED (log out and back in)

  Phase 3: zsh-ecosystem    [after: shell-foundation]
    • install-ohmyzsh       omz       ○ would skip (state: 2026-06-14)
    • install-zsh-plugins   script    would run
    • reload-zshrc          shell     would run

  ...
```

---

## 8. New Domain Tests (additions to Phase 9)

| Test class | Scenarios |
|---|---|
| `PhaseExecutionPlannerTest` | Linear deps → correct order; diamond deps → valid topo order; cycle → throws; empty → empty list |
| `PhaseExecutionPlannerTest` | `computeBlocked`: failed phase A blocks B and C that depend on A; independent D not blocked |
| `DotbotExecutorTest` | Mock ShellRunner exits 0 → Success; exits 1 → Failure; binary not found → Failure with clear message |
| `DefaultShellExecutorTest` | Shell binary exists → runs chsh; binary missing → Failure without running chsh; probe reads /etc/passwd correctly |
| `OhMyZshExecutorTest` | RUNZSH=no and CHSH=no are always set; installer exit 0 → Success; installer exit 1 → Failure |
| `ToolchainExecutorTest` | rustup env vars set correctly; script downloaded then executed; temp file deleted on success and failure |
| `NerdFontExecutorTest` | Config written to temp YAML; binary called with --config; temp file cleaned up |
| `ShellReloadExecutorTest` | zsh --login -i -c exit → Success; non-zero exit → Failure with helpful message |
| `LoginShellWrappingRunnerTest` | Command wrapped in zsh --login -i -c; args with spaces quoted; env passed through |
| `OutputAdapterSelectionTest` | --no-tui → PlainOutputAdapter; TTY available + no flag → TuiOutputAdapter; no TTY → PlainOutputAdapter |

---

## 9. Updated Example: Full Fedora Bootstrap Config

This is the canonical reference config that reflects the real workflow from
`system-bootstrap/scripts/fedora/`:

```yaml
profile: fedora-workstation
os:
  type: fedora
  release: "41"

phases:
  # ─── Phase 0: Dotfiles ────────────────────────────────────────────────────
  - name: dotfiles
    description: "Link dotfiles via dotbot-go"
    dependsOn: []
    restartPolicy: { type: none }
    modules:
      - type: dotbot
        name: link-dotfiles
        config: ~/.dotfiles/install.conf.yaml
        dotbotBinary: dotbot
        probeCommand: "test -L ~/.zshrc && test -L ~/.tmux.conf"

  # ─── Phase 1: Shell Foundation ────────────────────────────────────────────
  - name: shell-foundation
    description: "zsh + tmux, set zsh as default shell"
    dependsOn: [dotfiles]
    continueOnModuleError: false
    restartPolicy:
      type: prompt-restart
      message: |
        Default shell changed to zsh.
        Please log out and log back in, then run:
          sysboot run -c ~/path/to/fedora.yaml --skip-already-installed
    modules:
      - type: packages
        name: shell-and-terminal
        packageManager: dnf
        continueOnError: false
        packages: [zsh, tmux, zsh-completions]

      - type: default-shell
        name: set-default-shell
        shell: /bin/zsh
        probeCommand: "getent passwd $USER | cut -d: -f7 | grep -q zsh"

  # ─── Phase 2: Zsh Ecosystem ───────────────────────────────────────────────
  - name: zsh-ecosystem
    description: "Oh-My-Zsh, plugins"
    dependsOn: [shell-foundation]
    restartPolicy:
      type: requires-new-shell
      shell: zsh
    modules:
      - type: oh-my-zsh
        name: install-ohmyzsh
        installDir: ~/.oh-my-zsh
        probeCommand: "test -d ~/.oh-my-zsh"

      - type: shell-script
        name: install-powerlevel10k
        script: scripts/install-p10k.sh
        probeCommand: "test -d ~/.oh-my-zsh/custom/themes/powerlevel10k"
        continueOnError: true

      - type: shell-script
        name: install-zsh-plugins
        script: scripts/install-zsh-plugins.sh
        probeCommand: |
          test -d ~/.oh-my-zsh/custom/plugins/zsh-syntax-highlighting &&
          test -d ~/.oh-my-zsh/custom/plugins/zsh-autosuggestions &&
          test -d ~/.oh-my-zsh/custom/plugins/zsh-completions
        continueOnError: true

      - type: shell-reload
        name: validate-zshrc
        description: "Validate zsh config loads without errors"
        shell: zsh

  # ─── Phase 3: Language Toolchains ─────────────────────────────────────────
  - name: toolchains
    description: "rustup, juliaup, sdkman"
    dependsOn: [zsh-ecosystem]
    restartPolicy:
      type: requires-new-shell
      shell: zsh
    modules:
      - type: toolchain
        name: install-rustup
        kind: rustup
        installScript: "https://sh.rustup.rs"
        installArgs: ["-y", "--no-modify-path"]
        postInstallEnvSource: "~/.cargo/env"
        probeCommand: "test -f ~/.cargo/bin/rustup"
        continueOnError: true

      - type: toolchain
        name: install-juliaup
        kind: juliaup
        installScript: "https://install.julialang.org"
        installArgs: ["-y"]
        probeCommand: "test -f ~/.juliaup/bin/juliaup"
        continueOnError: true

      - type: toolchain
        name: install-sdkman
        kind: sdkman
        installScript: "https://get.sdkman.io"
        installArgs: []
        postInstallEnvSource: "~/.sdkman/bin/sdkman-init.sh"
        probeCommand: "test -d ~/.sdkman && test -f ~/.sdkman/bin/sdkman-init.sh"
        continueOnError: true

  # ─── Phase 4: DNF Packages ────────────────────────────────────────────────
  - name: dnf-packages
    description: "Core CLI tools and dev packages"
    dependsOn: [shell-foundation]   # independent of toolchains
    restartPolicy: { type: none }
    modules:
      - type: packages
        name: core-cli-tools
        packageManager: dnf
        continueOnError: true
        packages:
          - git
          - git-delta
          - curl
          - wget
          - htop
          - bottom
          - ripgrep
          - fd-find
          - bat
          - eza
          - fzf
          - jq
          - zellij
          - lazygit
          - just
          - tealdeer
          - direnv
          - stow

      - type: packages
        name: dev-dependencies
        packageManager: dnf
        continueOnError: true
        packages:
          - gcc
          - gcc-c++
          - clang
          - cmake
          - make
          - pkg-config
          - openssl-devel
          - libffi-devel
          - readline-devel
          - zlib-devel

      - type: packages
        name: container-tools
        packageManager: dnf
        continueOnError: true
        packages:
          - podman
          - buildah
          - skopeo
          - docker-compose

  # ─── Phase 5: Fonts ───────────────────────────────────────────────────────
  - name: fonts
    description: "Nerd Fonts via nerdfont-install"
    dependsOn: [dnf-packages]
    restartPolicy: { type: none }
    modules:
      - type: nerd-fonts
        name: install-nerd-fonts
        nerdfontBinary: nerdfont-install
        probeCommand: "fc-list | grep -qi 'JetBrainsMono'"
        config:
          release: latest
          destination: ~/.local/share/fonts/NerdFonts
          refreshFontCache: true
          families:
            - JetBrainsMono
            - Hack
            - FiraCode
            - Meslo
            - SymbolsOnly

  # ─── Phase 6: Compiled Binaries ───────────────────────────────────────────
  - name: binaries
    description: "Compiled binaries not in dnf"
    dependsOn: [dnf-packages]
    restartPolicy: { type: none }
    modules:
      - type: compiled-binary
        name: install-neovim
        binaryName: nvim
        url: https://github.com/neovim/neovim/releases/download/v0.10.1/nvim-linux-x86_64.tar.gz
        installPath: ~/.local/bin/nvim
        versionCommand: "nvim --version"
        expectedVersion: "0.10"
        probeCommand: "command -v nvim && nvim --version | grep -q 'v0.10'"

      - type: compiled-binary
        name: install-yazi
        binaryName: yazi
        url: https://github.com/sxyazi/yazi/releases/latest/download/yazi-x86_64-unknown-linux-gnu.zip
        installPath: ~/.local/bin/yazi
        probeCommand: "command -v yazi"

  # ─── Phase 7: Flatpaks ────────────────────────────────────────────────────
  - name: flatpaks
    description: "Flatpak desktop applications"
    dependsOn: [dnf-packages]
    restartPolicy: { type: none }
    modules:
      - type: flatpak
        name: desktop-apps
        remote: flathub
        continueOnError: true
        appIds:
          - com.spotify.Client
          - org.telegram.desktop
          - com.obsproject.Studio
          - org.gimp.GIMP
          - md.obsidian.Obsidian
```

---

## 10. Hard Constraints (additions)

- [ ] Phase DAG is topologically sorted at plan time; any cycle is a fatal validation error.
- [ ] `PromptRestart` phases: sysboot exits with code 0 after saving state. The next run with `--skip-already-installed` resumes from the first non-completed phase.
- [ ] `RequiresNewShell` wraps all shell commands in the affected phase via `LoginShellWrappingRunner`. The orchestrator switches runners per-phase, not per-command.
- [ ] `OhMyZshExecutor` always sets `RUNZSH=no` and `CHSH=no`. This is non-negotiable.
- [ ] `ToolchainExecutor` downloads install scripts to temp files and runs them; it never pipes curl directly to sh without a temp file intermediary.
- [ ] Temp files created during execution are always deleted in a `finally` block, even on failure.
- [ ] Plain mode (`--no-tui`) must work fully in a non-TTY environment (e.g. piped output in CI). `SudoPasswordProvider` in non-TTY plain mode returns `Optional.empty()` and the step is marked `Failure` with a clear message: "Cannot prompt for sudo password in non-interactive mode. Re-run in a terminal."
- [ ] `sysboot plan` is always safe: it makes zero filesystem changes, zero network calls (unless `--check-urls`), zero package manager calls.
- [ ] Phase `continueOnModuleError: false` (default: true) means the first module failure aborts the phase and marks all dependent phases as `Blocked`.
- [ ] A `Blocked` phase is shown in TUI in gray with a `⊘ blocked (waiting on: <phase>)` label.

# SYSBOOT — Master Engineering Prompt (v2)
# For a swarm of principal Java engineers

---

## Preamble: How to Use This Document

This prompt is **authoritative and complete**. It supersedes and consolidates:

- `AGENT-PROMPT-sysboot.md`
- `ADDENDUM-idempotency-and-state.md`
- `ADDENDUM-phases-ordering-ux.md`

All three remain valid for fine-grained implementation detail, but this document
defines scope, ownership, interfaces, and acceptance criteria for the full swarm.

Each engineer owns a **track** (see §3). Tracks communicate only through the
interfaces defined in §4. No track may reach into another track's package tree.
Every interface is sealed at the boundary defined here; changes to boundaries
require explicit cross-track agreement documented in a short ADR file.

---

## 1. Product Summary

**`sysboot`** is a GraalVM native binary that automates the full post-install
setup of a Linux workstation from a declarative YAML config. It replaces a
collection of hand-maintained shell scripts in
https://github.com/w0rxbend/system-bootstrap.

### What it does

1. Reads a YAML profile declaring an OS target and an ordered DAG of **phases**.
2. Each phase contains **modules** (install packages, run scripts, link dotfiles,
   install toolchains, install fonts, etc.).
3. Executes modules in dependency order, with full per-item error isolation.
4. Persists a **state file** so re-runs safely skip already-done work.
5. Renders a live **TUI** (TamboUI + JLine3) or plain **CLI** output (picocli).
6. Handles interactive sudo prompts, shell restarts, and `$PATH` reloads without
   leaving the TUI or losing state.

### What it is NOT

- Not a configuration management tool (not Ansible, not Chef).
- Not a package manager.
- Not a dotfiles manager (delegates to `dotbot-go`).
- Not a font manager (delegates to `nerdfont-install`).

---

## 2. Non-Negotiable Technical Decisions

| Decision | Rationale |
|---|---|
| **Java 24**, `--enable-preview` for stable features only | Pattern matching, sealed types, records, virtual threads |
| **Mill 0.12.x** build tool, no Gradle/Maven | Simpler for pure Java, deterministic, native-image friendly |
| **GraalVM 24 native image**, `--no-fallback` | Single binary, instant startup, no JVM on target machine |
| **Zero runtime DI** — hand-wired `ApplicationContext` | No reflection for wiring, full GraalVM compatibility |
| **Constructor injection only**, all deps via interfaces | Testability, OCP, no surprises |
| **TamboUI 0.3.x** + JLine3 backend | The only Java TUI library with ratatui parity |
| **Picocli 4.7.x** + `picocli-codegen` annotation processor | Native-image reflection config generated at compile time |
| **Jackson YAML** (jackson-dataformat-yaml) for config | Mature, GraalVM-compatible with reflect config |
| **Per-package process invocations** for all package managers | One failed package must never block others |
| **Atomic state writes** (temp file + `ATOMIC_MOVE`) | Crash-safe; no partial state corruption |
| **Virtual threads** (Java 21+) for parallel probes | No thread pool sizing; JVM schedules |
| **`char[]` for passwords**, zeroed after use | Never in logs, never in state file, never as `String` |

---

## 3. Track Ownership

The swarm is divided into **7 tracks**. Each track is a Mill module (or group of
modules). No cross-track source dependencies except through the ports defined in §4.

```
Track 1: DOMAIN          (core module)
Track 2: CONFIG          (config-parser module)
Track 3: EXECUTION       (executor module)
Track 4: STATE           (state module)
Track 5: TUI             (tui module)
Track 6: CLI             (cli module)
Track 7: APP + INFRA     (app module + build.sc + graal/ + CI)
```

### Track dependency graph (strict — no cycles, no skipping)

```
CLI ──────────────────────────────────────────┐
                                               ↓
APP ←── TUI ←── EXECUTION ←── STATE ←── CONFIG ←── DOMAIN
         ↑                      ↑
         └──────────────────────┘
         (TUI depends on STATE for live status display)
```

---

## 4. Cross-Track Ports (All in `core` module — Track 1 owns these)

These are the **only** interfaces through which tracks communicate.
All are in package `dev.sysboot.core.port`.

```java
// ── PRIMARY PORTS (what CLI/TUI calls) ──────────────────────────────────

public interface BootstrapOrchestrator {
    ExecutionSummary execute(BootstrapConfig config, ExecutionOptions options, ExecutionEventListener listener);
    ExecutionPlan plan(BootstrapConfig config, ExecutionOptions options);
}

public interface ConfigLoader {
    BootstrapConfig load(Path configFile);  // throws ConfigLoadException (unchecked)
}

public interface ConfigValidator {
    ValidationResult validate(BootstrapConfig config);
}

// ── SECONDARY PORTS (what EXECUTION implements) ─────────────────────────

public interface PackageManagerExecutor {
    boolean supports(PackageManagerKind kind);
    StepResult install(PackageName packageName, SudoContext sudoContext);
}

public interface ModuleExecutor<M extends BootstrapModule> {
    Class<M> moduleType();
    StepResult execute(M module, ExecutionContext context);
}

public interface ShellRunner {
    ProcessResult run(List<String> command, Map<String, String> env, Duration timeout);
}

public interface PtyShellRunner extends ShellRunner {
    // Same contract, but runs in a pseudo-terminal (for interactive sudo)
}

// ── STATE PORTS ──────────────────────────────────────────────────────────

public interface StateRepository {
    Optional<BootstrapState> load(String profileName);
    void recordItemSuccess(String profileName, StateEntry entry);
    void recordPhaseCompletion(String profileName, String phaseName);
    void deleteState(String profileName);
    void forgetItem(String profileName, String itemKey, ItemType itemType);
}

public interface SkipPolicy {
    SkipDecision evaluate(String itemKey, ItemType itemType);
}

// ── OUTPUT PORTS (what TUI/CLI implements) ──────────────────────────────

public interface ExecutionEventListener {
    void onEvent(ExecutionEvent event);
}

public interface SudoPasswordProvider {
    Optional<char[]> requestPassword(String prompt);  // char[], never String
}

public interface OutputAdapter {
    ExecutionEventListener eventListener();
    SudoPasswordProvider sudoPasswordProvider();
    void beforeRun(ExecutionPlan plan);
    void afterRun(ExecutionSummary summary);
}
```

---

## 5. Complete Domain Model (Track 1)

Package: `dev.sysboot.core.domain`

### 5.1 OS Target

```java
public sealed interface OsTarget permits FedoraTarget, ArchTarget, OpenSuseTarget, DebianTarget {
    String displayName();
}
public record FedoraTarget(String release) implements OsTarget {
    public String displayName() { return "Fedora " + release; }
}
public record ArchTarget() implements OsTarget {
    public String displayName() { return "Arch Linux"; }
}
public record OpenSuseTarget(String variant) implements OsTarget {
    // variant: "tumbleweed" | "leap-15.6"
    public String displayName() { return "openSUSE " + variant; }
}
public record DebianTarget(String release) implements OsTarget {
    public String displayName() { return "Debian/Ubuntu " + release; }
}
```

### 5.2 Value Objects

```java
public record PackageName(String value) {
    public PackageName {
        Objects.requireNonNull(value);
        if (value.isBlank()) throw new IllegalArgumentException("Package name must not be blank");
        if (value.chars().anyMatch(c -> " $;|&`><\"'\\".indexOf(c) >= 0))
            throw new IllegalArgumentException("Package name contains unsafe characters: " + value);
    }
}
public record PhaseName(String value) { /* non-blank validation */ }
public record ModuleName(String value) { /* non-blank validation */ }
public record ProfileName(String value) { /* non-blank validation */ }
public record ScriptPath(Path value) { /* non-null */ }
public record BinaryInstallPath(Path value) { /* non-null, must be absolute */ }
public record ProbeCommand(String shellExpression) { /* non-blank */ }
```

### 5.3 Module Sealed Hierarchy

```java
public sealed interface BootstrapModule permits
    PackageModule,       // dnf / pacman / paru / yay / apt / zypper
    FlatpakModule,       // flatpak install
    ShellScriptModule,   // arbitrary shell script
    CompiledBinaryModule,// download + extract + install binary
    DotbotModule,        // dotbot-go for symlinking dotfiles
    DefaultShellModule,  // chsh -s <shell>
    OhMyZshModule,       // oh-my-zsh installer (RUNZSH=no CHSH=no)
    ToolchainModule,     // rustup / juliaup / sdkman / goenv / pyenv
    NerdFontModule,      // delegates to nerdfont-install binary
    ShellReloadModule    // validates shell init in a new subprocess
{
    ModuleName name();
    Optional<ProbeCommand> probeCommand();
    boolean continueOnError();
}
```

### 5.4 Phase

```java
public record Phase(
    PhaseName name,
    String description,
    List<BootstrapModule> modules,
    List<PhaseName> dependsOn,
    RestartPolicy restartPolicy,
    boolean continueOnModuleError   // default true; false = abort phase on first failure
) {}

public sealed interface RestartPolicy permits
    RestartPolicy.None,
    RestartPolicy.PromptLogout,    // requires full logout/login (chsh, docker group)
    RestartPolicy.RequiresNewShell // requires new shell env (rustup, sdkman PATH)
{
    record None() implements RestartPolicy {}
    record PromptLogout(String message) implements RestartPolicy {}
    record RequiresNewShell(ShellKind shell) implements RestartPolicy {}
}

public enum ShellKind { ZSH, BASH, SH }
```

### 5.5 Top-Level Config

```java
public record BootstrapConfig(
    ProfileName profileName,
    OsTarget target,
    List<Phase> phases           // unordered in config; planner sorts by dependsOn
) {
    public BootstrapConfig {
        Objects.requireNonNull(profileName);
        Objects.requireNonNull(target);
        phases = List.copyOf(Objects.requireNonNull(phases));
        if (phases.isEmpty()) throw new IllegalArgumentException("At least one phase required");
    }
}
```

### 5.6 Execution Options

```java
public record ExecutionOptions(
    boolean skipAlreadyInstalled,
    boolean reProbe,
    boolean dryRun,
    boolean parallelPhases,
    Set<PhaseName> phaseFilter,      // empty = all phases
    Optional<PhaseName> fromPhase,   // skip phases before this one
    boolean verbose
) {}
```

### 5.7 Result Types

```java
public sealed interface StepResult permits
    StepResult.Success, StepResult.Failure, StepResult.Skipped, StepResult.DryRun
{
    record Success(String item, String version, Duration elapsed) implements StepResult {}
    record Failure(String item, String errorMessage, int exitCode, Duration elapsed) implements StepResult {}
    record Skipped(String item, SkipReason reason) implements StepResult {}
    record DryRun(String item, List<String> wouldExecute) implements StepResult {}
}

public sealed interface SkipReason permits SkipReason.FromState, SkipReason.FromProbe {
    record FromState(Instant recordedAt, String version) implements SkipReason {}
    record FromProbe(String detectedVersion) implements SkipReason {}
}

public sealed interface InstallationStatus permits
    InstallationStatus.Installed, InstallationStatus.NotInstalled, InstallationStatus.Unknown
{
    record Installed(String item, String detectedVersion) implements InstallationStatus {}
    record NotInstalled(String item) implements InstallationStatus {}
    record Unknown(String item, String reason) implements InstallationStatus {}
}

public sealed interface SkipDecision permits SkipDecision.Skip, SkipDecision.Run {
    record Skip(String itemKey, SkipReason reason) implements SkipDecision {}
    record Run(String itemKey) implements SkipDecision {}
}
```

### 5.8 Events

```java
public record ExecutionEvent(
    PhaseName phase,
    ModuleName module,
    String item,
    EventKind kind,
    StepResult result,      // null for STARTED events
    String logLine,         // null except for LOG_LINE events
    Instant timestamp
) {}

public enum EventKind {
    PHASE_STARTED, PHASE_COMPLETED, PHASE_BLOCKED,
    MODULE_STARTED, MODULE_COMPLETED,
    ITEM_STARTED, ITEM_COMPLETED,
    SUDO_REQUESTED, SUDO_COMPLETED,
    LOG_LINE,
    RESTART_REQUIRED
}
```

---

## 6. YAML Config Schema (complete, canonical)

```yaml
profile: <string>           # required; used as state file name

os:
  type: fedora | arch | opensuse | debian   # required
  release: <string>                          # required for fedora/debian; optional for arch/opensuse

phases:
  - name: <string>                           # required; unique
    description: <string>                    # optional
    dependsOn: [<phase-name>, ...]           # optional; empty list = no deps
    continueOnModuleError: true | false      # default: true
    restartPolicy:
      type: none                             # default
      # OR:
      type: prompt-logout
      message: <string>
      # OR:
      type: requires-new-shell
      shell: zsh | bash | sh

    modules:

      # ── Package installation ─────────────────────────────────────────────
      - type: packages
        name: <string>
        packageManager: dnf | pacman | paru | yay | apt | zypper
        continueOnError: true               # default: true (per-package isolation)
        packages:
          - <package-name>
        # No probeCommand needed — package managers have native query commands

      # ── Flatpak ──────────────────────────────────────────────────────────
      - type: flatpak
        name: <string>
        remote: flathub                     # default: flathub
        continueOnError: true
        appIds:
          - <app.id>

      # ── Shell script ─────────────────────────────────────────────────────
      - type: shell-script
        name: <string>
        script: <path>                      # relative to config file dir or absolute
        args: [<string>, ...]
        workingDir: <path>                  # default: config file dir
        continueOnError: false
        probeCommand: <shell-expr>          # optional; exit 0 = already done

      # ── Compiled binary ───────────────────────────────────────────────────
      - type: compiled-binary
        name: <string>
        binaryName: <string>               # display name
        url: <https-url>
        installPath: <absolute-path>
        versionCommand: <string>           # e.g. "nvim --version"
        expectedVersion: <string>          # prefix match; if differs → reinstall
        checksum:
          algorithm: sha256
          value: <hex>
        continueOnError: false
        probeCommand: <shell-expr>         # optional override

      # ── Dotbot ───────────────────────────────────────────────────────────
      - type: dotbot
        name: <string>
        config: <path>                     # path to install.conf.yaml
        dotbotBinary: dotbot               # binary name or absolute path
        probeCommand: <shell-expr>         # optional; e.g. "test -L ~/.zshrc"
        continueOnError: false

      # ── Default shell ─────────────────────────────────────────────────────
      - type: default-shell
        name: <string>
        shell: /bin/zsh                    # absolute path to shell binary
        # probeCommand auto-generated: getent passwd $USER | cut -d: -f7 | grep -q <shell>

      # ── Oh-My-Zsh ────────────────────────────────────────────────────────
      - type: oh-my-zsh
        name: <string>
        installDir: ~/.oh-my-zsh           # default
        # RUNZSH=no and CHSH=no always set — non-negotiable
        probeCommand: "test -d ~/.oh-my-zsh"

      # ── Toolchain ────────────────────────────────────────────────────────
      - type: toolchain
        name: <string>
        kind: rustup | juliaup | sdkman | goenv | pyenv | nvm | mise
        installScriptUrl: <https-url>      # downloaded to temp, then executed
        installArgs: [<string>, ...]       # e.g. ["-y", "--no-modify-path"]
        postInstallEnvSource: <path>       # file/dir to source in new shell
        probeCommand: <shell-expr>
        continueOnError: true

      # ── Nerd Fonts ────────────────────────────────────────────────────────
      - type: nerd-fonts
        name: <string>
        nerdfontBinary: nerdfont-install   # binary name or absolute path
        probeCommand: <shell-expr>         # e.g. "fc-list | grep -qi 'JetBrainsMono'"
        config:
          release: latest | v3.x.x
          destination: ~/.local/share/fonts/NerdFonts
          refreshFontCache: true
          families:
            - <FontFamilyName>

      # ── Shell reload ─────────────────────────────────────────────────────
      - type: shell-reload
        name: <string>
        description: <string>
        shell: zsh | bash | sh
        # Spawns: <shell> --login -i -c "echo 'OK'; exit 0"
        # Exit 0 = shell config valid; non-zero = init error
```

---

## 7. Real Config Files (Derived from system-bootstrap Analysis)

The following configs are derived from the full content of
https://github.com/w0rxbend/system-bootstrap — scripts, Justfile, README,
and known workflow order. These are the **canonical starting configs** to
ship with `sysboot`.

### 7.1 `config/fedora-workstation.yaml`

```yaml
profile: fedora-workstation
os:
  type: fedora
  release: "41"

phases:

  # ═══ PHASE 0: DOTFILES ════════════════════════════════════════════════════
  - name: dotfiles
    description: "Link dotfiles via dotbot-go (symlinks, directory creation, cleanup)"
    dependsOn: []
    restartPolicy:
      type: none
    modules:
      - type: dotbot
        name: link-dotfiles
        config: ~/.dotfiles/install.conf.yaml
        dotbotBinary: dotbot
        probeCommand: "test -L ~/.zshrc && test -L ~/.tmux.conf && test -L ~/.gitconfig"
        continueOnError: false

  # ═══ PHASE 1: SYSTEM UPDATE ═══════════════════════════════════════════════
  - name: system-update
    description: "Full system update and RPM Fusion repos"
    dependsOn: []
    continueOnModuleError: false
    restartPolicy:
      type: none
    modules:
      - type: shell-script
        name: system-update
        script: scripts/fedora/00-system-update.sh
        probeCommand: "dnf check-update --quiet; [ $? -ne 100 ]"
        continueOnError: false

      - type: shell-script
        name: enable-rpmfusion
        script: scripts/fedora/enable-rpmfusion.sh
        probeCommand: "dnf repolist | grep -q rpmfusion"
        continueOnError: false

  # ═══ PHASE 2: SHELL FOUNDATION ════════════════════════════════════════════
  - name: shell-foundation
    description: "Install zsh + tmux, set zsh as default shell"
    dependsOn: [system-update]
    continueOnModuleError: false
    restartPolicy:
      type: prompt-logout
      message: |
        ┌─────────────────────────────────────────────────────────────┐
        │  ⚠  Restart Required                                        │
        │                                                              │
        │  Your default shell has been changed to /bin/zsh.           │
        │  Please LOG OUT and LOG BACK IN, then re-run:               │
        │                                                              │
        │    sysboot run -c fedora-workstation.yaml \                 │
        │               --skip-already-installed                       │
        │                                                              │
        │  All completed steps will be skipped automatically.         │
        └─────────────────────────────────────────────────────────────┘
    modules:
      - type: packages
        name: shell-packages
        packageManager: dnf
        continueOnError: false
        packages:
          - zsh
          - zsh-completions
          - tmux
          - util-linux-user    # provides chsh

      - type: default-shell
        name: set-zsh-default
        shell: /bin/zsh
        continueOnError: false

  # ═══ PHASE 3: ZSH ECOSYSTEM ═══════════════════════════════════════════════
  - name: zsh-ecosystem
    description: "Oh-My-Zsh, Powerlevel10k, plugins, validate shell"
    dependsOn: [shell-foundation]
    restartPolicy:
      type: requires-new-shell
      shell: zsh
    modules:
      - type: oh-my-zsh
        name: install-ohmyzsh
        installDir: ~/.oh-my-zsh
        probeCommand: "test -d ~/.oh-my-zsh"
        continueOnError: false

      - type: shell-script
        name: install-powerlevel10k
        script: scripts/install-p10k.sh
        probeCommand: "test -d ~/.oh-my-zsh/custom/themes/powerlevel10k"
        continueOnError: true

      - type: shell-script
        name: install-zsh-autosuggestions
        script: scripts/install-zsh-plugin.sh
        args: ["zsh-users/zsh-autosuggestions"]
        probeCommand: "test -d ~/.oh-my-zsh/custom/plugins/zsh-autosuggestions"
        continueOnError: true

      - type: shell-script
        name: install-zsh-syntax-highlighting
        script: scripts/install-zsh-plugin.sh
        args: ["zsh-users/zsh-syntax-highlighting"]
        probeCommand: "test -d ~/.oh-my-zsh/custom/plugins/zsh-syntax-highlighting"
        continueOnError: true

      - type: shell-script
        name: install-zsh-completions-plugin
        script: scripts/install-zsh-plugin.sh
        args: ["zsh-users/zsh-completions"]
        probeCommand: "test -d ~/.oh-my-zsh/custom/plugins/zsh-completions"
        continueOnError: true

      - type: shell-reload
        name: validate-zsh-config
        description: "Validate .zshrc loads without errors in a new zsh session"
        shell: zsh

  # ═══ PHASE 4: TOOLCHAINS ══════════════════════════════════════════════════
  - name: toolchains
    description: "Language toolchains: rustup, juliaup, sdkman, go"
    dependsOn: [zsh-ecosystem]
    restartPolicy:
      type: requires-new-shell
      shell: zsh
    modules:
      - type: toolchain
        name: install-rustup
        kind: rustup
        installScriptUrl: "https://sh.rustup.rs"
        installArgs: ["-y", "--no-modify-path"]
        postInstallEnvSource: "~/.cargo/env"
        probeCommand: "test -f ~/.cargo/bin/rustup && ~/.cargo/bin/rustup --version"
        continueOnError: true

      - type: toolchain
        name: install-juliaup
        kind: juliaup
        installScriptUrl: "https://install.julialang.org"
        installArgs: ["-y"]
        probeCommand: "test -f ~/.juliaup/bin/juliaup"
        continueOnError: true

      - type: toolchain
        name: install-sdkman
        kind: sdkman
        installScriptUrl: "https://get.sdkman.io"
        installArgs: []
        postInstallEnvSource: "~/.sdkman/bin/sdkman-init.sh"
        probeCommand: "test -d ~/.sdkman && test -f ~/.sdkman/bin/sdkman-init.sh"
        continueOnError: true

      - type: toolchain
        name: install-mise
        kind: mise
        installScriptUrl: "https://mise.run"
        installArgs: []
        probeCommand: "command -v mise"
        continueOnError: true

  # ═══ PHASE 5: CORE CLI TOOLS (DNF) ═══════════════════════════════════════
  - name: dnf-core
    description: "Core CLI tools via DNF (independent of toolchains)"
    dependsOn: [system-update]
    restartPolicy:
      type: none
    modules:
      - type: packages
        name: file-tools
        packageManager: dnf
        continueOnError: true
        packages:
          - git
          - git-delta
          - curl
          - wget
          - rsync
          - unzip
          - tar
          - p7zip
          - p7zip-plugins

      - type: packages
        name: shell-tools
        packageManager: dnf
        continueOnError: true
        packages:
          - fzf
          - ripgrep
          - fd-find
          - bat
          - eza
          - sd
          - choose
          - bottom
          - htop
          - procs
          - tealdeer
          - jq
          - yq
          - direnv
          - stow

      - type: packages
        name: terminal-tools
        packageManager: dnf
        continueOnError: true
        packages:
          - zellij
          - wezterm
          - alacritty
          - kitty

      - type: packages
        name: dev-tools-dnf
        packageManager: dnf
        continueOnError: true
        packages:
          - just
          - lazygit
          - gcc
          - gcc-c++
          - clang
          - clang-tools-extra
          - cmake
          - make
          - pkg-config
          - openssl-devel
          - libffi-devel
          - readline-devel
          - zlib-devel
          - bzip2-devel
          - sqlite-devel
          - ncurses-devel

      - type: packages
        name: container-and-virt
        packageManager: dnf
        continueOnError: true
        packages:
          - podman
          - buildah
          - skopeo
          - docker-compose
          - distrobox
          - qemu-kvm
          - libvirt
          - virt-manager

      - type: packages
        name: system-tools
        packageManager: dnf
        continueOnError: true
        packages:
          - NetworkManager-tui
          - timeshift
          - lshw
          - inxi
          - dmidecode
          - hwinfo
          - pciutils
          - usbutils
          - acpi
          - smartmontools
          - lm_sensors
          - sysstat

      - type: packages
        name: media-codecs
        packageManager: dnf
        continueOnError: true
        packages:
          - ffmpeg
          - gstreamer1-plugins-bad-free
          - gstreamer1-plugins-bad-free-extras
          - gstreamer1-plugins-ugly
          - gstreamer1-libav
          - lame
          - x264
          - x265

      - type: packages
        name: gnome-tools
        packageManager: dnf
        continueOnError: true
        packages:
          - gnome-tweaks
          - gnome-extensions-app
          - dconf-editor

  # ═══ PHASE 6: COMPILED BINARIES ═══════════════════════════════════════════
  - name: compiled-binaries
    description: "Binaries not available in DNF or requiring latest versions"
    dependsOn: [dnf-core]
    restartPolicy:
      type: none
    modules:
      - type: compiled-binary
        name: install-neovim
        binaryName: nvim
        url: "https://github.com/neovim/neovim/releases/download/v0.10.1/nvim-linux-x86_64.tar.gz"
        installPath: ~/.local/bin/nvim
        versionCommand: "nvim --version"
        expectedVersion: "0.10"
        probeCommand: "command -v nvim && nvim --version | head -1 | grep -q 'v0.10'"
        continueOnError: true

      - type: compiled-binary
        name: install-yazi
        binaryName: yazi
        url: "https://github.com/sxyazi/yazi/releases/latest/download/yazi-x86_64-unknown-linux-gnu.zip"
        installPath: ~/.local/bin/yazi
        probeCommand: "command -v yazi"
        continueOnError: true

      - type: compiled-binary
        name: install-zoxide
        binaryName: zoxide
        url: "https://github.com/ajeetdsouza/zoxide/releases/latest/download/zoxide-x86_64-unknown-linux-musl.tar.gz"
        installPath: ~/.local/bin/zoxide
        probeCommand: "command -v zoxide"
        continueOnError: true

      - type: compiled-binary
        name: install-starship
        binaryName: starship
        url: "https://github.com/starship/starship/releases/latest/download/starship-x86_64-unknown-linux-musl.tar.gz"
        installPath: ~/.local/bin/starship
        probeCommand: "command -v starship"
        continueOnError: true

      - type: compiled-binary
        name: install-delta
        binaryName: delta
        url: "https://github.com/dandavison/delta/releases/latest/download/delta-x86_64-unknown-linux-musl.tar.gz"
        installPath: ~/.local/bin/delta
        probeCommand: "command -v delta"
        continueOnError: true

  # ═══ PHASE 7: CLI TOOLS (INSTALL SCRIPTS) ═════════════════════════════════
  - name: cli-tools-scripts
    description: "Tools installed via their own install scripts (not DNF)"
    dependsOn: [toolchains, dnf-core]
    restartPolicy:
      type: requires-new-shell
      shell: zsh
    modules:
      - type: shell-script
        name: install-tpm
        script: scripts/install-tpm.sh
        probeCommand: "test -d ~/.tmux/plugins/tpm"
        continueOnError: true

      - type: shell-script
        name: install-dotbot
        script: scripts/install-dotbot.sh
        probeCommand: "command -v dotbot"
        continueOnError: false

      - type: shell-script
        name: install-nerdfont-installer
        script: scripts/install-nerdfont-installer.sh
        probeCommand: "command -v nerdfont-install"
        continueOnError: false

  # ═══ PHASE 8: NERD FONTS ══════════════════════════════════════════════════
  - name: nerd-fonts
    description: "Nerd Fonts via nerdfont-install"
    dependsOn: [cli-tools-scripts]
    restartPolicy:
      type: none
    modules:
      - type: nerd-fonts
        name: install-fonts
        nerdfontBinary: nerdfont-install
        probeCommand: "fc-list | grep -qi 'JetBrainsMono Nerd Font'"
        config:
          release: latest
          destination: ~/.local/share/fonts/NerdFonts
          refreshFontCache: true
          families:
            - JetBrainsMono
            - Hack
            - FiraCode
            - Meslo
            - NerdFontsSymbolsOnly

  # ═══ PHASE 9: FLATPAKS ════════════════════════════════════════════════════
  - name: flatpaks
    description: "Flatpak desktop apps from Flathub"
    dependsOn: [dnf-core]
    restartPolicy:
      type: none
    modules:
      - type: shell-script
        name: enable-flathub
        script: scripts/enable-flathub.sh
        probeCommand: "flatpak remotes | grep -q flathub"
        continueOnError: false

      - type: flatpak
        name: communication
        remote: flathub
        continueOnError: true
        appIds:
          - org.telegram.desktop
          - com.discordapp.Discord
          - io.github.mimbrero.WhatsAppDesktop

      - type: flatpak
        name: media-and-creative
        remote: flathub
        continueOnError: true
        appIds:
          - com.spotify.Client
          - com.obsproject.Studio
          - org.gimp.GIMP
          - org.inkscape.Inkscape
          - org.blender.Blender
          - org.kde.kdenlive

      - type: flatpak
        name: productivity
        remote: flathub
        continueOnError: true
        appIds:
          - md.obsidian.Obsidian
          - com.notion.Notion
          - com.github.johnfactotum.Foliate

      - type: flatpak
        name: dev-tools-flatpak
        remote: flathub
        continueOnError: true
        appIds:
          - com.getpostman.Postman
          - io.dbeaver.DBeaverCommunity
          - com.usebruno.Bruno

  # ═══ PHASE 10: HYPRLAND (OPTIONAL) ═══════════════════════════════════════
  - name: hyprland
    description: "Hyprland WM and dependencies (optional, Fedora)"
    dependsOn: [dnf-core, nerd-fonts]
    restartPolicy:
      type: none
    modules:
      - type: packages
        name: hyprland-packages
        packageManager: dnf
        continueOnError: true
        packages:
          - hyprland
          - waybar
          - wofi
          - hyprpaper
          - hyprlock
          - hypridle
          - xdg-desktop-portal-hyprland
          - dunst
          - swayidle
          - swaylock
          - grim
          - slurp
          - wl-clipboard
          - cliphist
          - brightnessctl
          - playerctl
          - pipewire
          - wireplumber
          - pavucontrol

  # ═══ PHASE 11: CONFIGURE SYSTEM ═══════════════════════════════════════════
  - name: system-configuration
    description: "System config: git, docker group, time sync, hostname"
    dependsOn: [dnf-core]
    restartPolicy:
      type: prompt-logout
      message: |
        Your user has been added to the 'docker' group.
        Please log out and log back in for group membership to take effect.
    modules:
      - type: shell-script
        name: configure-git
        script: scripts/configure-git.sh
        probeCommand: "git config --global user.name | grep -q ."
        continueOnError: true

      - type: shell-script
        name: configure-time
        script: scripts/configure-time.sh
        probeCommand: "timedatectl show | grep -q 'NTP=yes'"
        continueOnError: true

      - type: shell-script
        name: docker-group
        script: scripts/configure-docker-group.sh
        probeCommand: "groups $USER | grep -q docker"
        continueOnError: true
```

### 7.2 `config/arch-workstation.yaml`

```yaml
profile: arch-workstation
os:
  type: arch

phases:

  - name: dotfiles
    description: "Link dotfiles via dotbot-go"
    dependsOn: []
    restartPolicy: { type: none }
    modules:
      - type: dotbot
        name: link-dotfiles
        config: ~/.dotfiles/install.conf.yaml
        dotbotBinary: dotbot
        probeCommand: "test -L ~/.zshrc && test -L ~/.tmux.conf"
        continueOnError: false

  - name: system-update
    description: "Full system update"
    dependsOn: []
    continueOnModuleError: false
    restartPolicy: { type: none }
    modules:
      - type: shell-script
        name: pacman-update
        script: scripts/arch/00-system-update.sh
        probeCommand: "checkupdates 2>/dev/null | wc -l | grep -q '^0$'"
        continueOnError: false

      - type: shell-script
        name: install-yay
        script: scripts/arch/install-yay.sh
        probeCommand: "command -v yay"
        continueOnError: false

  - name: shell-foundation
    description: "zsh + tmux + set default shell"
    dependsOn: [system-update]
    continueOnModuleError: false
    restartPolicy:
      type: prompt-logout
      message: "Default shell changed to zsh. Log out and back in, then re-run sysboot with --skip-already-installed."
    modules:
      - type: packages
        name: shell-packages
        packageManager: pacman
        continueOnError: false
        packages:
          - zsh
          - tmux
          - zsh-completions

      - type: default-shell
        name: set-zsh-default
        shell: /bin/zsh

  - name: zsh-ecosystem
    description: "Oh-My-Zsh, p10k, plugins"
    dependsOn: [shell-foundation]
    restartPolicy:
      type: requires-new-shell
      shell: zsh
    modules:
      - type: oh-my-zsh
        name: install-ohmyzsh
        probeCommand: "test -d ~/.oh-my-zsh"
        continueOnError: false

      - type: shell-script
        name: install-p10k
        script: scripts/install-p10k.sh
        probeCommand: "test -d ~/.oh-my-zsh/custom/themes/powerlevel10k"
        continueOnError: true

      - type: shell-script
        name: install-zsh-plugins
        script: scripts/install-zsh-plugins.sh
        probeCommand: "test -d ~/.oh-my-zsh/custom/plugins/zsh-syntax-highlighting"
        continueOnError: true

      - type: shell-reload
        name: validate-zsh
        shell: zsh

  - name: toolchains
    description: "rustup, juliaup, mise"
    dependsOn: [zsh-ecosystem]
    restartPolicy:
      type: requires-new-shell
      shell: zsh
    modules:
      - type: toolchain
        name: install-rustup
        kind: rustup
        installScriptUrl: "https://sh.rustup.rs"
        installArgs: ["-y", "--no-modify-path"]
        probeCommand: "test -f ~/.cargo/bin/rustup"
        continueOnError: true

      - type: toolchain
        name: install-juliaup
        kind: juliaup
        installScriptUrl: "https://install.julialang.org"
        installArgs: ["-y"]
        probeCommand: "test -f ~/.juliaup/bin/juliaup"
        continueOnError: true

  - name: pacman-packages
    description: "Core packages via pacman"
    dependsOn: [system-update]
    restartPolicy: { type: none }
    modules:
      - type: packages
        name: core-tools
        packageManager: pacman
        continueOnError: true
        packages:
          - git
          - curl
          - wget
          - rsync
          - unzip
          - fzf
          - ripgrep
          - fd
          - bat
          - eza
          - bottom
          - htop
          - procs
          - jq
          - direnv
          - just
          - lazygit
          - zoxide
          - starship
          - base-devel
          - cmake
          - clang
          - openssl
          - zellij

  - name: aur-packages
    description: "AUR packages via yay"
    dependsOn: [pacman-packages]
    restartPolicy: { type: none }
    modules:
      - type: packages
        name: aur-tools
        packageManager: yay
        continueOnError: true
        packages:
          - yazi
          - wezterm
          - git-delta
          - tealdeer
          - mise-bin
          - neovim-nightly-bin

  - name: hyprland
    description: "Hyprland WM (Arch)"
    dependsOn: [pacman-packages]
    restartPolicy: { type: none }
    modules:
      - type: packages
        name: hyprland-packages
        packageManager: pacman
        continueOnError: true
        packages:
          - hyprland
          - waybar
          - wofi
          - hyprpaper
          - dunst
          - grim
          - slurp
          - wl-clipboard
          - xdg-desktop-portal-hyprland
          - pipewire
          - wireplumber
          - pavucontrol
          - brightnessctl
          - playerctl

      - type: packages
        name: hyprland-aur
        packageManager: yay
        continueOnError: true
        packages:
          - hyprlock
          - hypridle
          - cliphist
          - swww

  - name: nerd-fonts
    dependsOn: [pacman-packages]
    restartPolicy: { type: none }
    modules:
      - type: nerd-fonts
        name: install-fonts
        nerdfontBinary: nerdfont-install
        probeCommand: "fc-list | grep -qi 'JetBrainsMono'"
        config:
          release: latest
          destination: ~/.local/share/fonts/NerdFonts
          refreshFontCache: true
          families: [JetBrainsMono, Hack, FiraCode, Meslo, NerdFontsSymbolsOnly]

  - name: flatpaks
    dependsOn: [pacman-packages]
    restartPolicy: { type: none }
    modules:
      - type: packages
        name: install-flatpak
        packageManager: pacman
        continueOnError: false
        packages: [flatpak]

      - type: shell-script
        name: enable-flathub
        script: scripts/enable-flathub.sh
        probeCommand: "flatpak remotes | grep -q flathub"

      - type: flatpak
        name: apps
        remote: flathub
        continueOnError: true
        appIds:
          - com.spotify.Client
          - org.telegram.desktop
          - md.obsidian.Obsidian
          - com.obsproject.Studio
```

### 7.3 `config/opensuse-workstation.yaml`

```yaml
profile: opensuse-workstation
os:
  type: opensuse
  release: tumbleweed

phases:

  - name: dotfiles
    dependsOn: []
    restartPolicy: { type: none }
    modules:
      - type: dotbot
        name: link-dotfiles
        config: ~/.dotfiles/install.conf.yaml
        dotbotBinary: dotbot
        probeCommand: "test -L ~/.zshrc"
        continueOnError: false

  - name: system-update
    dependsOn: []
    restartPolicy: { type: none }
    modules:
      - type: shell-script
        name: zypper-update
        script: scripts/opensuse/00-system-update.sh
        probeCommand: "zypper list-updates 2>/dev/null | grep -q 'No updates found'"
        continueOnError: false

  - name: shell-foundation
    dependsOn: [system-update]
    continueOnModuleError: false
    restartPolicy:
      type: prompt-logout
      message: "Default shell changed to zsh. Log out and back in, then re-run sysboot with --skip-already-installed."
    modules:
      - type: packages
        name: shell-packages
        packageManager: zypper
        continueOnError: false
        packages: [zsh, tmux, zsh-completions, util-linux]

      - type: default-shell
        name: set-zsh-default
        shell: /bin/zsh

  - name: zsh-ecosystem
    dependsOn: [shell-foundation]
    restartPolicy:
      type: requires-new-shell
      shell: zsh
    modules:
      - type: oh-my-zsh
        name: install-ohmyzsh
        probeCommand: "test -d ~/.oh-my-zsh"
      - type: shell-reload
        name: validate-zsh
        shell: zsh

  - name: toolchains
    dependsOn: [zsh-ecosystem]
    restartPolicy:
      type: requires-new-shell
      shell: zsh
    modules:
      - type: toolchain
        name: install-rustup
        kind: rustup
        installScriptUrl: "https://sh.rustup.rs"
        installArgs: ["-y", "--no-modify-path"]
        probeCommand: "test -f ~/.cargo/bin/rustup"
        continueOnError: true

  - name: zypper-packages
    dependsOn: [system-update]
    restartPolicy: { type: none }
    modules:
      - type: packages
        name: core-tools
        packageManager: zypper
        continueOnError: true
        packages:
          - git
          - curl
          - wget
          - fzf
          - ripgrep
          - fd
          - bat
          - eza
          - jq
          - htop
          - lazygit
          - just
          - zellij
          - gcc
          - gcc-c++
          - clang
          - cmake
          - make
          - openssl-devel

  - name: nerd-fonts
    dependsOn: [zypper-packages]
    restartPolicy: { type: none }
    modules:
      - type: nerd-fonts
        name: install-fonts
        nerdfontBinary: nerdfont-install
        probeCommand: "fc-list | grep -qi 'JetBrainsMono'"
        config:
          release: latest
          destination: ~/.local/share/fonts/NerdFonts
          refreshFontCache: true
          families: [JetBrainsMono, Hack, FiraCode, Meslo, NerdFontsSymbolsOnly]

  - name: flatpaks
    dependsOn: [zypper-packages]
    restartPolicy: { type: none }
    modules:
      - type: packages
        name: install-flatpak
        packageManager: zypper
        continueOnError: false
        packages: [flatpak]

      - type: shell-script
        name: enable-flathub
        script: scripts/enable-flathub.sh
        probeCommand: "flatpak remotes | grep -q flathub"

      - type: flatpak
        name: apps
        remote: flathub
        continueOnError: true
        appIds:
          - com.spotify.Client
          - org.telegram.desktop
          - md.obsidian.Obsidian
```

---

## 8. Track-by-Track Work Orders

### Track 1 — DOMAIN (`core`)
Owner: Most senior engineer. All other tracks block on this.

Deliverables:
- All sealed type hierarchies from §5 (zero dependencies, pure Java records/interfaces)
- All value objects with validation
- All port interfaces from §4
- `ExecutionOptions`, `ExecutionPlan`, `ExecutionSummary` records
- 100% unit test coverage on all value object validations
- Zero production dependencies (no Jackson, no JLine, nothing)

Acceptance criteria:
- `mill core.test` passes
- No class > 150 lines
- All collections returned unmodifiable
- No null returns from public methods

---

### Track 2 — CONFIG (`config-parser`)
Depends on: Track 1 (core) only

Deliverables:
- `YamlConfigLoader implements ConfigLoader`
- `YamlConfigValidator implements ConfigValidator`
- DTOs in `dev.sysboot.config.dto` (Jackson-annotated, separate from domain)
- `ConfigMapper` (DTO → domain)
- `PhaseExecutionPlanner` (topological sort, cycle detection via Kahn's)
- All three example config files parsed successfully
- Unit tests: valid configs, invalid YAML, missing required fields, cycles, unknown phase refs, unsafe package names

Acceptance criteria:
- All three config files in §7 parse without error
- Cycle in `dependsOn` → `ConfigValidationException` with the cycle path in the message
- `PhaseExecutionPlanner` output is deterministic for the same input
- GraalVM reflect config generated for all DTO classes

---

### Track 3 — EXECUTION (`executor`)
Depends on: Track 1 (core), Track 4 (state)

Deliverables:
- `DefaultShellRunner`
- `PtyShellRunner` (pty4j)
- `LoginShellWrappingRunner` (wraps any ShellRunner for `RequiresNewShell`)
- All `PackageManagerExecutor` implementations: `DnfPackageInstaller`, `PacmanPackageInstaller`, `ParuPackageInstaller`, `YayPackageInstaller`, `AptPackageInstaller`, `ZypperPackageInstaller`
- All `ModuleExecutor` implementations: `FlatpakExecutor`, `ShellScriptExecutor`, `CompiledBinaryExecutor`, `DotbotExecutor`, `DefaultShellExecutor`, `OhMyZshExecutor`, `ToolchainExecutor`, `NerdFontExecutor`, `ShellReloadExecutor`
- All probe implementations: `DnfProbe`, `PacmanProbe`, `AptProbe`, `ZypperProbe`, `FlatpakProbe`, `CompiledBinaryProbe`, `ShellScriptProbe` (probe-command-based)
- `InstalledProbeRegistry`
- `ParallelProbeRunner` (virtual threads)
- `BootstrapOrchestratorImpl`
- `SudoContext` value object (carries `SudoPasswordProvider` + current phase context)
- `ModuleExecutorRegistry`

Critical implementation rules:
- Each package = separate process call (never batch)
- `OhMyZshExecutor` always sets `RUNZSH=no CHSH=no`
- `ToolchainExecutor` always downloads to temp file before exec
- Temp files always deleted in `finally`
- Probe timeout: 10s for packages, 30s for shell probes
- `Unknown` probe result always = `Run` (fail-safe)

---

### Track 4 — STATE (`state`)
Depends on: Track 1 (core) only

Deliverables:
- `BootstrapState`, `StateEntry`, `PhaseStateEntry` records
- `JsonStateRepository implements StateRepository`
  - Atomic writes (temp file + ATOMIC_MOVE)
  - Corrupt file → warn + Optional.empty() (never throw)
  - Per-profile files: `~/.local/share/sysboot/<profile>.state.json`
- `SkipEvaluatorImpl implements SkipPolicy`
  - State file → probe → fail-safe Run
  - `reProbe` flag bypasses state file
- Unit tests: load/save/corrupt/atomic-write/idempotent-record/forget-item

---

### Track 5 — TUI (`tui`)
Depends on: Track 1 (core), Track 4 (state)

Deliverables:
- `TuiOutputAdapter implements OutputAdapter`
- `TuiExecutionEventListener implements ExecutionEventListener` (queue-based, drains on tick)
- `TuiSudoPasswordProvider implements SudoPasswordProvider` (SynchronousQueue)
- All TUI screens as sealed `AppScreen` hierarchy:
  - `DashboardScreen` — profile picker, detected OS, config files found
  - `ProbeScreen` — parallel probe progress with summary
  - `PlanScreen` — phase DAG, what would run vs skip
  - `ExecutionScreen` — phase list left, item table right, live stdout bottom
  - `SudoPromptScreen` — modal overlay with masked TextInput
  - `RestartRequiredScreen` — full-screen message, re-run command, remaining phases
  - `SummaryScreen` — installed/skipped/failed counts, retry button, state file path
  - `LogScreen` — scrollable full log
- `AppStateController` — manages screen transitions

TUI rules:
- All rendering is pure (no side effects in render functions)
- Events consumed on tick (max 100/tick), never in render
- Passwords: `SynchronousQueue<char[]>`, zeroed after delivery
- Color scheme: GREEN=success, RED=failure, CYAN=skipped-state, BLUE=skipped-probe, YELLOW=running, WHITE/DIM=pending, GRAY=blocked

Screen transition map:
```
LAUNCHED → DashboardScreen
DashboardScreen [Enter] → ProbeScreen (if --skip-already-installed) OR PlanScreen
ProbeScreen [Enter] → PlanScreen
PlanScreen [Enter] → ExecutionScreen
ExecutionScreen [sudo needed] → SudoPromptScreen (overlay)
SudoPromptScreen [Enter/Esc] → ExecutionScreen
ExecutionScreen [phase done, RestartRequired] → RestartRequiredScreen
RestartRequiredScreen [Enter] → EXIT 0
ExecutionScreen [all done] → SummaryScreen
SummaryScreen [r] → ExecutionScreen (retry failed)
SummaryScreen [q] → EXIT 0
Any screen [l] → LogScreen
LogScreen [Esc] → return to previous screen
```

---

### Track 6 — CLI (`cli`)
Depends on: Track 1 (core)

Deliverables:
- `SysbootCommand` (root, mixes in `TuiCommand` from `tamboui-picocli`)
- Subcommands: `RunCommand`, `DryRunCommand`, `ValidateCommand`, `PlanCommand`, `StatusCommand`, `StateCommand` (with `show`/`reset`/`forget`/`path` sub-subcommands), `GenerateCommand`
- `PlainOutputAdapter implements OutputAdapter`
- `StdoutEventListener implements ExecutionEventListener` (ANSI via picocli `Help.Ansi`)
- `StdinPasswordProvider implements SudoPasswordProvider` (Console.readPassword)
- `NonInteractiveSudoProvider` (returns empty for CI/non-TTY with clear error message)
- Auto-detect: if `System.console() == null` → `NonInteractiveSudoProvider`
- Auto-detect: if not a TTY → `PlainOutputAdapter` regardless of `--no-tui`

All options from §7 of the agent prompt, plus:
- `--phase NAME,...` filter
- `--from-phase NAME`
- `--parallel-phases`

---

### Track 7 — APP + INFRA (`app`, `build.sc`, `graal/`, CI)
Depends on: All tracks

Deliverables:
- `ApplicationContext` — full compile-time wiring, no reflection
- `Main` class
- Complete `build.sc` with all modules, deps, tasks
- `nativeImage` Mill task with all required flags
- `graal/reflect-config.json` (generated via agent run)
- `graal/resource-config.json`
- GitHub Actions CI: `./mill test`, `./mill app.nativeImage`, release artifacts for x86_64 and aarch64
- `README.md`, `CONTRIBUTING.md`, `docs/config-schema.md`
- Shell install script: `curl -sSf https://sysboot.sh/install.sh | sh`

---

## 9. Interface Stability Rules

1. Track 1 interfaces are **frozen** once any other track begins implementation.
   Changes require a written ADR filed in `docs/adr/`.
2. No track may import from another track's non-port package.
   Enforced by Mill module isolation.
3. All inter-track communication is via **interfaces in `core`**, never concrete classes.
4. `ApplicationContext` is the **only** place where concrete classes are referenced.
   It lives in `app`, which all other tracks treat as a black box.

---

## 10. Acceptance Criteria (Definition of Done)

### Functionality
- [ ] `sysboot run -c fedora-workstation.yaml` runs all phases in correct order
- [ ] `sysboot run -c fedora-workstation.yaml --skip-already-installed` skips completed phases from state file
- [ ] `sysboot run -c fedora-workstation.yaml --skip-already-installed --re-probe` skips only probe-confirmed items
- [ ] `sysboot validate -c fedora-workstation.yaml` exits 0 for valid, 1 for invalid
- [ ] `sysboot plan -c fedora-workstation.yaml` prints DAG with skip/run decisions, no side effects
- [ ] `sysboot status -c fedora-workstation.yaml` shows per-item status, no installs
- [ ] `sysboot state show` prints JSON state file
- [ ] `sysboot state reset --profile fedora-workstation` prompts, then deletes
- [ ] Phase with `restartPolicy: prompt-logout` exits cleanly, prints re-run command, resumes on next run
- [ ] Phase with `restartPolicy: requires-new-shell` wraps all commands in login shell
- [ ] `OhMyZshModule` always sets RUNZSH=no CHSH=no
- [ ] All toolchain scripts downloaded to temp file, never piped directly
- [ ] All temp files deleted after use (success and failure)
- [ ] Each package installed as a separate process; one failure does not block others
- [ ] State file writes are atomic; crash mid-run leaves valid state
- [ ] Corrupt state file → warning + continues (no crash)
- [ ] `--no-tui` mode works in a non-TTY (piped output, CI)
- [ ] Sudo password never logged, never in state file

### Quality
- [ ] All value objects: invalid input throws `IllegalArgumentException` with clear message
- [ ] Cycle in phase `dependsOn` → `ConfigValidationException` with cycle path
- [ ] All returned collections are unmodifiable
- [ ] No public methods return null
- [ ] No field injection anywhere
- [ ] No static mutable state anywhere
- [ ] No class > 300 lines; no method > 20 lines
- [ ] No `catch (Exception e)` — specific types only
- [ ] Test names follow `scenario_condition_expected` convention
- [ ] Mill builds cleanly: `./mill __.test` all green

### Binary
- [ ] `./mill app.nativeImage` produces `sysboot` binary
- [ ] Binary size < 60 MB
- [ ] Cold startup < 150ms (`time sysboot --version`)
- [ ] Binary works on Fedora 41, Arch, openSUSE Tumbleweed without JVM installed


 w
