# Architecture

Fluxion is a Mill-built Java 25 CLI for Linux bootstrap workflows. The active source tree still
lives under `sysboot/` and keeps Java packages under `dev.sysboot` to avoid broad package churn.
The application is split into small modules with a strict dependency direction:

```text
cli -> app -> tui -> executor -> config-parser -> core
```

## Modules

`core` contains records, sealed interfaces, value objects, and ports. It has no production
dependencies and should not import process, terminal, YAML, or framework APIs.

`config-parser` maps YAML DTOs into the core domain model with Jackson YAML. Reflective DTOs must be
registered in `graal/reflect-config.json` when added.

`WorkstationProfileConfigMapper` and `WorkstationProfileValidator` are thin coordinators.
`PlanKinds` remains the single plan-kind dispatch table, while constructor-injected package/file,
structured-command, tooling/trust, and system/control helpers own cohesive rules. Shared support
objects contain only deterministic field, path, checksum, and default mechanics, preserving error
order and reflective DTO behavior without recreating kind switches.

`executor` owns shell execution, package-manager adapters, probes, state persistence, and
orchestration. External effects are hidden behind core ports such as `ShellRunner`.

`BootstrapOrchestratorImpl` is the composition boundary for three execution stages:

```text
source setup -> phase planning -> module/item execution
```

`SourceSetupRunner` handles repository and remote-source prerequisites.
`PhaseExecutionRunner` owns dependency order, cancellation boundaries, phase fingerprints, and
resume state. `ModuleDispatcher` routes registered `ModuleExecutor` implementations, table-driven
single-item bindings, and the remaining multi-item modules through `DirectModuleExecutor`.
Per-item events, skip decisions, and successful state writes converge in `ItemExecution`.

`tui` owns terminal UI screens, sudo prompting, and event-listener integration.

`app` wires collaborators with constructor injection in `ApplicationContext`. Do not add runtime DI,
service locators, classpath scanning, or framework containers.

`cli` owns Picocli commands, argument parsing, exit-code mapping, and process entry points.

## Error Boundaries

Command classes should throw domain or infrastructure exceptions when lower layers fail. The CLI
entry point maps those exceptions to stable exit codes and concise stderr messages. Normal
user-facing failures should not print stack traces.

## Native-Image Constraints

Avoid runtime class loading, dynamic proxies, and unregistered reflection. Prefer explicit command
objects, DTOs, and compile-time wiring. When adding Jackson DTOs or Picocli commands, update
`graal/reflect-config.json` or verify that Picocli codegen emits the required metadata.

## State And Resume

State is stored under `~/.local/share/fluxion`. Item entries track successful work, and phase entries
include fingerprints derived from phase/module configuration. A completed phase is skipped only when
its saved fingerprint matches the current config, so changing package lists or commands causes the
phase to run again.

State files are written through a private temporary file and an atomic replacement. Profile names
are constrained to safe slugs, state roots and files reject symbolic links, and profile mutations
hold both a process-local lock and an operating-system file lock so concurrent Fluxion processes do
not lose each other's updates.

Restart checkpoints emit resume guidance in plain CLI mode. `status --resume-command` computes the
next incomplete phase from saved state and prints a rerun command with `--skip-already-installed`.

## Trust Boundaries

Structured argument vectors are preferred whenever user-controlled values cross into a process.
Shell-backed compatibility steps remain explicit in the profile and are redacted before their text
reaches logs, events, state, JSON, or the TUI.

Remote scripts, toolchain installers, compiled binaries, repository keys, and repository descriptor
files are verified before execution or privileged mutation. Remote transport is HTTPS without URL
credentials, downloads are bounded and deadline-controlled, and temporary artifacts are private.
Compiled binaries require an exact SHA-256 binding or a detached signature from the configured full
signer fingerprint. Repository signing keys are similarly pinned rather than trusted solely because
they were served by a configured URL.
