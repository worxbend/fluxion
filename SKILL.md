# SKILL: Java · Mill · GraalVM Native Image · Picocli · TamboUI

> Read this entire file before writing a single line of code.
> Every constraint here is non-negotiable unless explicitly overridden by the task prompt.

---

## 1. Toolchain & Build System

### Java Version
- **Target: Java 24** (latest LTS-equivalent; use `--enable-preview` only if sealed types / pattern matching features are needed and stabilised).
- Source/target compatibility: `JavaVersion.VERSION_24`.
- Enable **records**, **sealed interfaces**, **pattern matching for switch**, **text blocks** everywhere appropriate.

### Build Tool: Mill
- Use **Mill 0.12.x** (latest stable). Do NOT use Gradle, Maven, or SBT.
- Module structure follows Mill conventions: `build.sc` at root, one `object` per module.
- Use `mill.scalalib.JavaModule` (not Scala). All modules are pure Java.
- Dependency management: `ivy"group:artifact:version"` syntax in `ivyDeps`.
- Always pin exact versions — no `+` wildcards in production deps.
- Use `mill.define.Cross` for any cross-cutting concerns.
- GraalVM native image via `mill-native-image` plugin or manual `ExecCommand` task — see §3.

### Key Dependencies (pin these exact versions or latest stable at task time)
```scala
// build.sc — reference versions
val picocliVersion       = "4.7.6"
val tambuiVersion        = "0.3.0"   // snapshot: use sonatype snapshots repo
val jacksonVersion       = "2.17.2"  // for YAML config parsing
val slf4jVersion         = "2.0.13"
val logbackVersion       = "1.5.6"
val junitVersion         = "5.11.0"
val assertjVersion       = "3.26.3"
val mockitoVersion       = "5.12.0"
```

### Repositories (add to build.sc)
```scala
def repositoriesTask = T.task {
  super.repositoriesTask() ++ Seq(
    coursier.MavenRepository("https://central.sonatype.com/repository/maven-snapshots/")
  )
}
```

---

## 2. Project Module Layout

```
sysboot/                          # root
├── build.sc                      # Mill build definition
├── .mill-version                 # e.g. 0.12.3
├── config/                       # Example YAML configs (not source)
│   └── example-fedora.yaml
├── docs/
├── core/                         # Mill module: domain model + pure logic
│   └── src/dev/sysboot/core/
├── config-parser/                # Mill module: YAML → domain model
│   └── src/dev/sysboot/config/
├── executor/                     # Mill module: package-manager executors
│   └── src/dev/sysboot/executor/
├── tui/                          # Mill module: TamboUI screens & components
│   └── src/dev/sysboot/tui/
├── cli/                          # Mill module: picocli entry-point (thin)
│   └── src/dev/sysboot/cli/
└── app/                          # Mill module: wires everything, main class
    └── src/dev/sysboot/app/
```

**Dependency direction (strict — no cycles):**
```
cli → app → tui → executor → config-parser → core
```
Each module only depends on modules to its right. `core` has zero production dependencies.

---

## 3. GraalVM Native Image

### Build approach
Add a Mill task in the `app` module:

```scala
def nativeImage = T {
  val jar = assembly()
  val out = T.dest / "sysboot"
  os.proc(
    "native-image",
    "-jar", jar.path,
    "--no-fallback",
    "--enable-preview",
    "-H:+ReportExceptionStackTraces",
    "-H:ReflectionConfigurationFiles=" + (millSourcePath / "graal" / "reflect-config.json"),
    "-H:ResourceConfigurationFiles=" + (millSourcePath / "graal" / "resource-config.json"),
    "--initialize-at-build-time=org.slf4j",
    "-o", out
  ).call(stdout = os.Inherit, stderr = os.Inherit)
  PathRef(out)
}
```

### GraalVM compatibility rules
- **No reflection** unless registered in `graal/reflect-config.json`.
- **No dynamic class loading**. All polymorphism via interfaces known at compile time.
- **Jackson YAML**: use `jackson-dataformat-yaml` with `@JsonDeserialize` annotations; generate reflect config with the native-image agent during tests (`-agentlib:native-image-agent=config-output-dir=graal/`).
- **JLine / TamboUI backend**: use `tamboui-jline3-backend`; JLine has native-image support — add `--initialize-at-run-time=org.jline` to native-image args.
- **Picocli**: add `picocli-codegen` annotation processor; it generates `reflect-config.json` automatically at compile time.
  ```scala
  def javacOptions = Seq("-proc:only") // or include in compileIvyDeps
  def compileIvyDeps = Agg(ivy"info.picocli:picocli-codegen:4.7.6")
  ```
- **Process API** (`ProcessBuilder`, `Runtime.exec`): fully supported in native image.
- **PTY / sudo interaction**: use `pty4j` or direct `/dev/pts` via Panama; register Panama foreign calls if used.

---

## 4. Architecture & Design Principles

### Package naming
`dev.sysboot.<module>.<layer>` — e.g. `dev.sysboot.executor.dnf`.

### Layered architecture (Hexagonal / Ports & Adapters)
```
┌──────────────────────────────────────────────────┐
│  CLI / TUI (Adapters — Primary)                  │
├──────────────────────────────────────────────────┤
│  Application Layer (Use Cases / Commands)        │
├──────────────────────────────────────────────────┤
│  Domain Layer (Entities, Value Objects, Ports)   │
├──────────────────────────────────────────────────┤
│  Infrastructure (Adapters — Secondary)           │
│  executor.dnf / executor.pacman / config.yaml    │
└──────────────────────────────────────────────────┘
```

### Compile-time DI (mandatory)
- **NO** Spring, Quarkus, CDI, or any runtime DI container.
- Wire everything in `ApplicationContext` (a plain Java class in the `app` module) using constructor injection only.
- Every class receives its dependencies via its constructor. No field injection. No setters.
- Use `static factory methods` on `ApplicationContext` for named variants (e.g. `forFedora()`, `forArch()`).
- All collaborators are referenced by **interface**, never by concrete class, except inside the `app` wiring module.

### SOLID
- **S**: One reason to change per class. `DnfPackageInstaller` installs packages — it does not parse config.
- **O**: New package managers added by implementing `PackageManagerExecutor` — zero changes to existing code.
- **L**: Every `PackageManagerExecutor` implementation is fully substitutable.
- **I**: Separate `Queryable`, `Installable`, `Removable` interfaces; not one fat `PackageManager`.
- **D**: High-level use cases depend on `PackageManagerExecutor` interface, not `DnfExecutor`.

### GRASP
- **Information Expert**: `BootstrapConfig` knows how to validate itself.
- **Creator**: `ModuleFactory` creates `BootstrapModule` instances from config.
- **Controller**: `BootstrapApplicationService` is the single facade for the TUI/CLI to call.
- **Low Coupling**: enforced by the module dependency graph above.
- **High Cohesion**: no "Utils", no "Helper" god classes.
- **Pure Fabrication**: `ProcessRunner` is a pure fabrication — no domain concept, but necessary infrastructure.
- **Indirection / Protected Variation**: `PackageManagerExecutor` interface shields domain from OS changes.

### GoF Patterns (use where they genuinely simplify; never cargo-cult)
- **Strategy**: each package manager is a Strategy (`PackageManagerExecutor`).
- **Command**: each bootstrap step is a `BootstrapCommand` with `execute()` and `rollback()` (for future undo).
- **Composite**: `CompositeModule` runs a list of `BootstrapModule` instances in order.
- **Observer / Listener**: `ExecutionEventListener` feeds progress events to the TUI without coupling executor to UI.
- **Template Method**: `AbstractShellExecutor` provides `buildCommand()` + `handleExit()` hooks.
- **Builder**: `BootstrapConfig.Builder` for constructing validated config objects.
- **Factory Method**: `PackageManagerExecutorFactory.forOs(OsTarget)`.
- **Decorator**: `SudoDecoratingExecutor` wraps any `ShellExecutor` to prepend `sudo`.

### Clean Code rules
- Methods ≤ 20 lines. Classes ≤ 200 lines (domain), ≤ 300 lines (infrastructure).
- No abbreviations in names (`dnfExec` → `dnfPackageInstaller`).
- No comments that say *what* the code does — only *why* when non-obvious.
- No `null` — use `Optional<T>` for absent values; use domain-specific sealed Result types for operation outcomes.
- No raw `String` for domain concepts — use value objects (`PackageName`, `ScriptPath`, `OsTarget`).
- No checked exceptions crossing layer boundaries — translate at the boundary to domain `BootstrapException`.
- No `static` mutable state anywhere.
- No public fields.
- All collections returned from methods are **unmodifiable**.

---

## 5. Domain Model (core module)

```java
// Sealed hierarchy for OS targets
public sealed interface OsTarget permits FedoraTarget, ArchTarget, OpenSuseTarget {}
public record FedoraTarget(String release) implements OsTarget {}
public record ArchTarget() implements OsTarget {}
public record OpenSuseTarget(String version) implements OsTarget {}

// Value objects
public record PackageName(String value) {
    public PackageName { Objects.requireNonNull(value); if (value.isBlank()) throw new IllegalArgumentException(); }
}
public record ScriptPath(Path value) {
    public ScriptPath { Objects.requireNonNull(value); }
}
public record BinaryUrl(URI value) {}

// Module types (sealed)
public sealed interface BootstrapModule permits
    PackageModule, FlatpakModule, ShellScriptModule, CompiledBinaryModule {}

public record PackageModule(
    String name,
    PackageManagerKind packageManager,
    List<PackageName> packages,
    boolean continueOnError   // per-package error isolation
) implements BootstrapModule {}

public enum PackageManagerKind { DNF, PACMAN, PARU, YAW, APT, FLATPAK }

public record FlatpakModule(String remote, List<String> appIds) implements BootstrapModule {}
public record ShellScriptModule(String name, ScriptPath script, List<String> args) implements BootstrapModule {}
public record CompiledBinaryModule(String name, BinaryUrl url, Path installPath) implements BootstrapModule {}

// Top-level config
public record BootstrapConfig(
    String profileName,
    OsTarget target,
    List<BootstrapModule> modules
) {}

// Execution result (sealed)
public sealed interface StepResult permits StepResult.Success, StepResult.Failure, StepResult.Skipped {}
// inner records...

// Event for TUI updates
public record ExecutionEvent(String moduleName, String packageName, StepResult result, Instant timestamp) {}
```

---

## 6. Ports (interfaces in core)

```java
// Primary port — what CLI/TUI calls
public interface BootstrapOrchestrator {
    void execute(BootstrapConfig config, ExecutionEventListener listener);
    void dryRun(BootstrapConfig config, ExecutionEventListener listener);
}

// Secondary port — what executors implement
public interface PackageManagerExecutor {
    boolean supports(PackageManagerKind kind);
    StepResult install(PackageName packageName);
}

public interface ShellRunner {
    ProcessResult run(List<String> command, Map<String, String> env, Duration timeout);
}

public interface SudoPasswordProvider {
    Optional<char[]> requestPassword(String prompt); // char[] — never String for passwords
}

public interface ExecutionEventListener {
    void onEvent(ExecutionEvent event);
}

public interface ConfigLoader {
    BootstrapConfig load(Path configFile);
}
```

---

## 7. Executor Module (infrastructure)

### Per-package installation (critical for DNF/APT)
Each package **must** be installed in a separate `ProcessBuilder` invocation so that one failed package never blocks others:

```java
public final class DnfPackageInstaller implements PackageManagerExecutor {

    private final ShellRunner shellRunner;
    private final SudoPasswordProvider sudoPasswordProvider;

    public DnfPackageInstaller(ShellRunner shellRunner, SudoPasswordProvider sudoPasswordProvider) {
        this.shellRunner = shellRunner;
        this.sudoPasswordProvider = sudoPasswordProvider;
    }

    @Override
    public boolean supports(PackageManagerKind kind) {
        return kind == PackageManagerKind.DNF;
    }

    @Override
    public StepResult install(PackageName packageName) {
        var command = List.of("sudo", "dnf", "install", "-y", packageName.value());
        var result = shellRunner.run(command, Map.of(), Duration.ofMinutes(10));
        return result.exitCode() == 0
            ? new StepResult.Success(packageName.value())
            : new StepResult.Failure(packageName.value(), result.stderr());
    }
}
```

Same pattern for `PacmanPackageInstaller`, `ParuPackageInstaller`, `AptPackageInstaller`, `FlatpakInstaller`.

### PTY / sudo interaction
When a command requires interactive sudo (password prompt):
- Use `pty4j` library to spawn the process in a pseudo-terminal.
- `PtyShellRunner` implements `ShellRunner`; it detects `[sudo] password` prompt patterns and calls `SudoPasswordProvider.requestPassword()`.
- The TUI implements `SudoPasswordProvider` by opening a modal password dialog.
- Passwords are held as `char[]` and zeroed after use.

---

## 8. Config YAML Schema

```yaml
# ~/.config/sysboot/fedora-workstation.yaml
profile: fedora-workstation
os:
  type: fedora
  release: "41"

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
      - zsh
      - tmux
      - neovim
      - just
      - zellij

  - type: packages
    name: dev-tools
    packageManager: dnf
    continueOnError: true
    packages:
      - gcc
      - clang
      - cmake
      - java-21-openjdk
      - nodejs
      - golang

  - type: flatpak
    name: desktop-apps
    remote: flathub
    appIds:
      - com.spotify.Client
      - org.telegram.desktop
      - com.obsproject.Studio

  - type: shell-script
    name: install-sdkman
    script: scripts/cli-tools.sh
    args: ["--sdkman"]

  - type: compiled-binary
    name: install-neovim
    url: https://github.com/neovim/neovim/releases/latest/download/nvim-linux-x86_64.tar.gz
    installPath: /usr/local/bin/nvim
```

---

## 9. TUI Design (tui module)

### Framework
- `tamboui-tui` module with `TuiRunner`.
- `tamboui-picocli` for picocli integration via `TuiCommand`.
- Backend: `tamboui-jline3-backend`.

### Screen states (sealed)
```java
public sealed interface AppScreen permits
    DashboardScreen, ModuleListScreen, ExecutionScreen, LogScreen, SudoPromptScreen {}
```

### ExecutionScreen layout
```
┌─ sysboot ── fedora-workstation ─────────────────────────────────┐
│ [████████████░░░░░░░░░░] 42%   Module: core-cli-tools           │
├─────────────────────────────────────────────────────────────────┤
│ PACKAGE               STATUS      DURATION                       │
│ git                   ✓ OK        0.8s                          │
│ curl                  ✓ OK        0.4s                          │
│ wget                  ✗ FAILED    1.2s  (see log)               │
│ htop                  ⟳ RUNNING   ...                           │
│ ripgrep               ○ PENDING                                  │
├─────────────────────────────────────────────────────────────────┤
│ [q] Quit  [l] Logs  [p] Pause  [s] Skip module                  │
└─────────────────────────────────────────────────────────────────┘
```

### SudoPromptScreen
Modal overlay using TamboUI `Clear` + bordered `Block` + `TextInput`:
```
╔═══════════════════════════════╗
║  🔐 Sudo Password Required    ║
║  Installing: dnf packages     ║
║                               ║
║  Password: [______________]   ║
║                               ║
║  [Enter] Confirm  [Esc] Cancel║
╚═══════════════════════════════╝
```

### Event threading
- Executors run on a **dedicated virtual thread** (Java 21+ `Thread.ofVirtual()`).
- `ExecutionEvent`s are pushed to a `java.util.concurrent.LinkedBlockingQueue`.
- TUI `TickEvent` handler drains the queue and updates render state.
- No shared mutable state between executor threads and TUI thread.

---

## 10. CLI Commands (cli module)

```
sysboot [GLOBAL OPTIONS] <COMMAND> [COMMAND OPTIONS]

Commands:
  run        Run a bootstrap profile
  dry-run    Show what would be executed without doing anything
  validate   Validate a config file
  list       List available modules in a config
  status     Show last execution status

Global options:
  -c, --config=FILE    Config file path [default: ~/.config/sysboot/default.yaml]
  -v, --verbose        Verbose output
  --no-tui             Disable TUI, use plain stdout

Examples:
  sysboot run -c fedora-workstation.yaml
  sysboot run -c arch.yaml --modules core-cli-tools,dev-tools
  sysboot dry-run -c fedora-workstation.yaml
  sysboot validate -c fedora-workstation.yaml
```

Picocli subcommand structure:
```java
@Command(name = "sysboot", mixinStandardHelpOptions = true, subcommands = {
    RunCommand.class, DryRunCommand.class, ValidateCommand.class,
    ListCommand.class, StatusCommand.class
})
public final class SysbootCli extends TuiCommand { ... }
```

---

## 11. Testing Strategy

### Unit tests (JUnit 5 + AssertJ + Mockito)
- Every domain class: pure unit test, zero I/O.
- Every executor: mock `ShellRunner`, verify correct commands constructed.
- `ConfigLoader`: test with real YAML strings (no file I/O needed).

### Integration tests (separate Mill module)
- `ShellRunnerIntegrationTest`: spawns real `echo` / `true` / `false` processes.
- `DnfInstallerIntegrationTest`: guarded by `@EnabledOnOs(OS.LINUX)` + `@EnabledIf("dnf.available")`.

### TUI tests
- Use `tamboui-core-assertj` for buffer comparison.
- Render screens with mock event queues and assert buffer state.

### Test module in Mill
```scala
object test extends Tests {
  def testFramework = "com.novocode.junit.JUnitFramework"
  def ivyDeps = Agg(
    ivy"org.junit.jupiter:junit-jupiter:5.11.0",
    ivy"org.assertj:assertj-core:3.26.3",
    ivy"org.mockito:mockito-core:5.12.0"
  )
}
```

---

## 12. Code Quality Gates

- **Checkstyle**: enforce Google Java Style with `max line length = 120`.
- **SpotBugs**: zero HIGH/MEDIUM bugs allowed.
- **PMD**: ruleset `java-bestpractices`, `java-design`, `java-errorprone`.
- **No `@SuppressWarnings`** without a comment explaining why.
- All Mill `T.task` outputs are **deterministic** (no timestamp in outputs).

---

## 13. Key Anti-Patterns to Avoid

| Anti-pattern | What to do instead |
|---|---|
| God class / Service | Split by responsibility; each class has one job |
| Anemic domain model | Put behaviour on domain objects |
| String-typed identifiers | Value objects (`PackageName`, `OsTarget`) |
| Null returns | `Optional<T>` or sealed `Result` |
| `instanceof` chains | Sealed interfaces + pattern-matching switch |
| Static utility methods with state | Inject collaborators |
| Catching `Exception` broadly | Catch specific, translate at boundary |
| Magic numbers/strings | Named constants or enum members |
| Mutable public fields | Immutable records everywhere |
| Comments explaining what | Self-documenting names; comment only why |

---

## 14. File Naming Conventions

- Interfaces: noun (role) — `PackageManagerExecutor`, `ConfigLoader`
- Implementations: concrete noun — `DnfPackageInstaller`, `YamlConfigLoader`
- Use cases: verb + noun — `RunBootstrapUseCase`, `ValidateConfigUseCase`
- Commands (picocli): `RunCommand`, `DryRunCommand`
- Screens (TUI): `ExecutionScreen`, `SudoPromptScreen`
- Events: past tense — `PackageInstalledEvent`, `ModuleCompletedEvent`
- Value objects: plain noun — `PackageName`, `ScriptPath`
- Enums: plural — `PackageManagerKinds` (or singular if singular makes sense: `OsFamily`)

---

## 15. Reference Links

- Mill docs: https://mill-build.org/mill/
- Picocli: https://picocli.info/
- Picocli + native image: https://picocli.info/picocli-on-graalvm.html
- TamboUI: https://github.com/tamboui/tamboui
- TamboUI picocli module: `tamboui-picocli`
- GraalVM native image: https://www.graalvm.org/latest/reference-manual/native-image/
- pty4j: https://github.com/JetBrains/pty4j
- Jackson YAML: https://github.com/FasterXML/jackson-dataformats-text/tree/master/yaml
