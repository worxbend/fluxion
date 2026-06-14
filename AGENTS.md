# AGENTS.md

Repository guidance for AI coding agents working in this checkout.

## Project Shape

This repository currently contains a new `sysboot/` project and deleted files from an older root Gradle application. Treat `sysboot/` as the active codebase unless the user explicitly asks about the deleted root project.

`sysboot` is a Java 25, Mill-built, config-driven Linux bootstrap tool. It reads YAML profiles and installs packages, Flatpaks, shell scripts, and compiled binaries with either a TUI or plain CLI output.

## Active Root

Run build and test commands from:

```bash
cd sysboot
```

Important files:

- `sysboot/build.mill.yaml` - root Mill YAML build header.
- `sysboot/*/package.mill.yaml` - module definitions.
- `sysboot/.mill-version` - pinned Mill version, currently `1.1.6`.
- `sysboot/README.md` - user-facing overview and commands.
- `sysboot/CONTRIBUTING.md` - style and testing rules.
- `sysboot/docs/config-schema.md` - YAML config schema.
- `SKILL.md` - project-specific Java/Mill/GraalVM constraints.

## Architecture

Module dependency direction is:

```text
cli -> app -> tui -> executor -> config-parser -> core
```

Keep this direction intact.

- `core`: domain model, value objects, ports, no production dependencies.
- `config-parser`: Jackson YAML DTOs and mapping into `core`.
- `executor`: package manager executors, shell runners, probes, state repository, orchestrator.
- `tui`: TamboUI screens, sudo prompt, event listener.
- `app`: compile-time dependency wiring in `ApplicationContext`.
- `cli`: Picocli commands and `Main`.

Do not introduce Spring, CDI, Quarkus, runtime DI, or service locators. Wire collaborators through constructors and `ApplicationContext`.

## Build And Test

Common commands:

```bash
mill __.test
mill core.test
mill config-parser.test
mill executor.test
mill cli.assembly
mill cli.nativeImage
```

Run a specific test class:

```bash
mill executor.test.testOnly dev.sysboot.executor.DnfPackageInstallerTest
```

Native image uses Mill `NativeImageModule` with `jvmVersion: graalvm-community:25`.

## Coding Rules

Follow `SKILL.md` and `sysboot/CONTRIBUTING.md`.

Key constraints:

- Java 25; use records, sealed interfaces, and pattern matching where they clarify code.
- Mill only; do not add Gradle, Maven, or SBT.
- Methods should stay small, approximately 20 lines or less.
- Domain classes should stay under roughly 200 lines; infrastructure under roughly 300 lines.
- Public methods must not return `null`; use `Optional` or domain result types.
- Return unmodifiable collections (`List.of`, `List.copyOf`, etc.).
- Use constructor injection only.
- Catch specific exceptions and translate them at layer boundaries.
- Keep comments sparse and focused on non-obvious reasons, not line-by-line narration.
- Do not let passwords or sudo input leak into logs or event output.

## Config Support

YAML profile schema is documented in `sysboot/docs/config-schema.md`.

Supported OS targets:

- `fedora`
- `arch`
- `opensuse`
- `debian`

Supported package managers:

- `dnf`
- `pacman`
- `paru`
- `yay`
- `apt`
- `zypper`

Module types include packages, Flatpak apps, shell scripts, compiled binaries, and additional shell/tooling modules represented in the current core and parser packages.

Be careful with current implementation maturity:

- `ConfigMapper` supports both legacy flat `modules` and newer `phases`.
- `PhaseExecutionPlanner` exists but is not currently used by `BootstrapOrchestratorImpl`.
- Several parsed module types have domain records and some executor classes, but are not yet handled by the main orchestrator switch.
- Example configs still use flat `modules`.

## Change Guidelines

Before editing:

1. Read the relevant module and tests.
2. Preserve existing module boundaries.
3. Prefer extending existing abstractions over creating parallel ones.
4. Add or update focused tests for changed behavior.
5. Update GraalVM reflection/resource config when adding reflective DTOs or resources.

When adding a package manager:

1. Add the enum value in `PackageManagerKind`.
2. Add an executor/probe implementation in `executor`.
3. Register it in `ApplicationContext`.
4. Add parser/config tests if user-facing YAML changes.

When adding config fields or module types:

1. Update DTOs in `config-parser`.
2. Update mapper logic.
3. Update domain records/value objects in `core`.
4. Add config parser tests and resource YAML fixtures.
5. Update `docs/config-schema.md`.
6. Check GraalVM reflection configuration.

## Worktree Notes

`initial-prompts.md` exists at the repository root and contains historical product prompts. Treat
the later "SYSBOOT — Master Engineering Prompt (v2)" section as product context, but prefer the
current `sysboot/build.mill.yaml`, module `package.mill.yaml` files, and docs when build-system
details conflict.
