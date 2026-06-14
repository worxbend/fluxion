# MEMORY.md

Persistent project context for future sessions.

## Current Understanding

The active project is `sysboot`, located under `sysboot/`. It is a Java 25 Mill YAML multi-module project that builds a Linux system bootstrap CLI/TUI and optional GraalVM native binary.

The old root-level Gradle Java app appears removed in the current worktree. The current root contains `SKILL.md` and `sysboot/` as untracked files. Avoid reverting, cleaning, or replacing this state without explicit user direction.

## Product Summary

`sysboot` lets users declare a Linux development environment in YAML and then applies it idempotently. It supports package installation, Flatpak apps, shell scripts, downloaded compiled binaries, probes for installed state, skip logic, per-item execution events, and persisted state.

Primary user commands are exposed through Picocli:

- `run`
- `dry-run`
- `validate`
- `list`
- `status`
- `state`

The CLI entry point is `dev.sysboot.cli.Main`.

## Technical Stack

- Java 24
- Mill 1.1.6 YAML build in `sysboot/build.mill.yaml` plus module `package.mill.yaml` files
- Picocli 4.7.6
- Jackson YAML 2.17.2
- TamboUI `0.3.0-SNAPSHOT`
- pty4j
- SLF4J and Logback
- JUnit 5, AssertJ, Mockito
- GraalVM native-image task at `mill cli.nativeImage`

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
- The parser supports `phases`, `dependsOn`, and `restartPolicy`, but `BootstrapOrchestratorImpl` currently executes `config.modules()` as a flattened list and does not use `PhaseExecutionPlanner`.
- `PhaseExecutionPlanner` exists and performs topological sorting plus blocked-phase computation, but it is not wired into `ApplicationContext` or the orchestrator.
- `BootstrapModule` permits newer module types (`DotbotModule`, `DefaultShellModule`, `OhMyZshModule`, `ToolchainModule`, `NerdFontModule`, `ShellReloadModule`). Executors for several exist in `executor`, but `BootstrapOrchestratorImpl` and `ParallelProbeRunner` only switch over packages, flatpaks, shell scripts, compiled binaries, and `ZypperModule`.
- `initial-prompts.md` exists at `/home/worxbend/Worxpace/fluxion/initial-prompts.md`. It contains older prompt sections plus an authoritative "SYSBOOT — Master Engineering Prompt (v2)" section starting around line 2890.
- `gh` and `jq` are installed (`gh` 2.93.0, `jq` 1.8.1). `mill` was not installed on `PATH`, so tests could not be run in this environment at analysis time.

## Intended Product From initial-prompts.md

The authoritative target is a GraalVM native binary named `sysboot` that automates full Linux workstation post-install setup from declarative YAML. It is not meant to be Ansible, a package manager, a dotfiles manager, or a font manager; it delegates dotfiles to `dotbot-go` and fonts to `nerdfont-install`.

The real target architecture is phase-based, not a flat module list:

- Config declares an OS target and a DAG of phases.
- Each phase declares modules plus `dependsOn`, `continueOnModuleError`, and `restartPolicy`.
- `restartPolicy` can be `none`, `prompt-logout`, or `requires-new-shell`.
- Execution must topologically sort phases, detect cycles at validation time, mark dependent phases blocked after hard failures, and persist completed phases.
- Re-running with `--skip-already-installed` must skip completed phases/items from state and continue after logout/restart interruptions.

The master prompt expects these core ports in `core` under a port package: `BootstrapOrchestrator`, `ConfigLoader`, `ConfigValidator`, `PackageManagerExecutor`, `ModuleExecutor`, `ShellRunner`, `PtyShellRunner`, `StateRepository`, `SkipPolicy`, `ExecutionEventListener`, `SudoPasswordProvider`, and `OutputAdapter`.

Expected CLI surface includes `run`, `dry-run`, `validate`, `plan`, `status`, `state show/reset/forget/path`, and `generate`, with run options for `--phase`, `--from-phase`, `--skip-already-installed`, `--re-probe`, `--dry-run`, `--parallel-phases`, `--no-tui`, and `--verbose`.

The canonical configs are phase-based workstation profiles: `fedora-workstation.yaml`, `arch-workstation.yaml`, and `opensuse-workstation.yaml`.

## Useful Commands

From `sysboot/`:

```bash
mill __.test
mill config-parser.test
mill executor.test
mill cli.assembly
java -jar out/cli/assembly.dest/out.jar validate -c config/example-fedora.yaml
java -jar out/cli/assembly.dest/out.jar run -c config/example-fedora.yaml --no-tui
```

Native image:

```bash
mill cli.nativeImage
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
