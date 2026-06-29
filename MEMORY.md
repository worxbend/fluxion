# MEMORY.md

Persistent project context for future sessions.

## Current Understanding

The active project lives under `sysboot/`, but the user-facing product and command name is
`fluxion`. It is a Java 25 Mill YAML multi-module project that builds a Linux workstation bootstrap
CLI/TUI and optional GraalVM native binary.

The old root-level Gradle Java app was removed before the current planning work. Avoid reverting,
cleaning, or replacing that state without explicit user direction.

## Product Summary

`fluxion` lets users declare a Linux development environment in YAML and then applies it
idempotently. It supports package installation, Flatpak apps, shell scripts, downloaded compiled
binaries, probes for installed state, skip logic, per-item execution events, and persisted state.

Primary user commands are exposed through Picocli:

- `apply`
- `run`
- `dry-run`
- `validate`
- `lint`
- `list`
- `plan`
- `graph`
- `diff`
- `explain`
- `status`
- `state`
- `generate`
- `snapshot`
- `import`
- `doctor`

The CLI entry point is `dev.sysboot.cli.Main`.

## Technical Stack

- Java 25
- Mill 1.1.6 YAML build in `sysboot/build.mill.yaml` plus module `package.mill.yaml` files
- Picocli 4.7.6
- Jackson YAML 2.17.2
- TamboUI `0.3.0-SNAPSHOT`
- pty4j
- SLF4J and Logback
- JUnit 5, AssertJ, Mockito
- GraalVM native-image task through `./mill cli.nativeImage`

## Source Map

- `sysboot/core/src/dev/sysboot/core`: domain records, value objects, ports, sealed result types.
- `sysboot/config-parser/src/dev/sysboot/config`: YAML loader, DTOs, mapper.
- `sysboot/executor/src/dev/sysboot/executor`: orchestrator, shell runners, package installers, probes, skip evaluator, JSON state repository.
- `sysboot/tui/src/dev/sysboot/tui`: TUI screens and listeners.
- `sysboot/app/src/dev/sysboot/app/ApplicationContext.java`: compile-time wiring.
- `sysboot/cli/src/dev/sysboot/cli`: Picocli commands.
- `sysboot/config`: example YAML profiles.
- `sysboot/graal`: native-image config.

## Design Constraints To Preserve

Dependency direction:

```text
cli -> app -> tui -> executor -> config-parser -> core
```

`core` should remain dependency-free. `executor` and lower layers must not import TUI classes. `ApplicationContext` is the wiring boundary.

Public APIs should use value objects and domain result types instead of raw strings or exceptions where practical. Collections returned from domain objects should be immutable.

## Known Observations

- `ApplicationContext.forCli(...)` currently stores `null` for `tuiApp`, even though project rules say public methods should not return `null`. If tightening quality later, consider replacing this with `Optional<SysbootTuiApp>` or separate context types.
- `BootstrapOrchestratorImpl.recordSuccess(...)` passes `null` for an error field in `StateEntry`; this may conflict with the repository's no-null preference depending on the `StateEntry` contract.
- `dryRunModule(...)` emits DNF-like package commands for generic `PackageModule`, even when the configured package manager may be apt, pacman, paru, yay, or zypper. Verify whether this is intentional before relying on dry-run output.
- `.mill-version` exists under `sysboot/` and is pinned to `1.1.6`.
- The stable config schema is `profile`/`os`/`jobs` with `steps`; legacy `phases` and top-level
  `modules` remain supported for compatibility.
- `BootstrapOrchestratorImpl` uses `PhaseExecutionPlanner` for ordered phase execution.
- `BootstrapModule` permits package, Flatpak, repository, shell, compiled-binary, dotfiles, shell,
  toolchain, assertion, and manual checkpoint module types. Package modules use the `ModuleExecutor`
  foundation; other module types still have direct orchestrator dispatch and can be migrated
  incrementally.
- `initial-prompts.md` exists at `/home/worxbend/Worxpace/fluxion/initial-prompts.md`. It contains older prompt sections plus an authoritative "SYSBOOT — Master Engineering Prompt (v2)" section starting around line 2890.
- `gh` and `jq` are installed (`gh` 2.93.0, `jq` 1.8.1).

## Manifest Direction

The current stable Fluxion schema is `jobs`/`steps`. A Kubernetes-style
`apiVersion: initkit.io/v1alpha1`, `kind: WorkstationProfile` manifest is planned as an
experimental second config frontend, not as a replacement for the Java 25 Fluxion implementation.
Keep `jobs`/`steps`, legacy `phases`, and legacy flat `modules` backward-compatible while that
manifest path is added.

The intended manifest behavior to layer onto the current implementation includes:

- Kubernetes-style top-level `apiVersion`, `kind`, `metadata`, and `spec`.
- Informational `spec.target.os`, with execution selection coming from host facts and `when`.
- Global `spec.policy`, `spec.vars`, `spec.sources`, and ordered `spec.plan` entries.
- Per-entry `execution`, conditional execution through `when`, and explicit interrupt checkpoints.

Do not rename the product away from `fluxion`, replace the current Java implementation, or make the
experimental manifest the preferred schema until it reaches feature parity for validation, dry-run,
state, and TUI display.

## Useful Commands

From `sysboot/`:

```bash
./mill __.test
./mill config-parser.test
./mill executor.test
./mill cli.assembly
java -jar out/cli/assembly.dest/out.jar validate -c config/example-fedora.yaml
java -jar out/cli/assembly.dest/out.jar run -c config/example-fedora.yaml --no-tui
```

Native image:

```bash
./mill cli.nativeImage
./out/cli/nativeImage.dest/native-executable --help
```

## Documentation To Keep In Sync

Update these when behavior changes:

- `sysboot/README.md`
- `sysboot/docs/config-schema.md`
- `sysboot/CONTRIBUTING.md`
- `sysboot/graal/*.json` for native-image reflective/resource changes
- `AGENTS.md` and this file when repository-level guidance changes

## Pending Chezmoi Research

The user provided a detailed research plan for analysing `twpayne/chezmoi` with the local `gh` CLI. The requested output directory is:

```bash
~/.local/share/sysboot-research/chezmoi
```

Expected primary deliverables:

- `FINDINGS.md`
- `RECOMMENDATIONS.md`
- issue JSON/detail files
- structure/package/interface files
- state-store and external-tool analysis files
- changelog and discussion extracts

Do not use a browser for that task. Use `gh`, `jq`, local clone inspection, and shell commands only. If data collection partially fails, still produce `FINDINGS.md` and mark unavailable evidence explicitly.
