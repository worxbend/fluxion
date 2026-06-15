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

`executor` owns shell execution, package-manager adapters, probes, state persistence, and the
orchestrator. External effects are hidden behind core ports such as `ShellRunner`.

Package modules already use the `ModuleExecutor` foundation. Other module types still dispatch
directly from the orchestrator and can be migrated incrementally.

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

Restart checkpoints emit resume guidance in plain CLI mode. `status --resume-command` computes the
next incomplete phase from saved state and prints a rerun command with `--skip-already-installed`.
