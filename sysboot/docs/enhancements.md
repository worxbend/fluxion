# Enhancement Backlog

This document captures useful product and engineering improvements discovered during the
documentation review. It is intentionally broader than `TODO.md`: items here can be promoted into
the main roadmap once their value and implementation shape are clear.

## Highest-Value Product Improvements

### Safer First Run

- Add `fluxion init` as a guided wrapper around `generate`, `doctor`, and `plan`.
- Add `fluxion run --confirm-plan` to require an explicit confirmation after showing the plan.
- Add `--assume-yes` for non-interactive runs where prompts should fail fast or proceed
  deterministically.
- Add `--require-clean-state` to refuse a run when state contains failed or interrupted phases.
- Add a pre-run state backup before any mutating command.

### Better Resume And Recovery

- Store interrupted phase reason and recommended next command in state.
- Show next runnable phase in `state show`.
- Add `fluxion resume` as an alias that resolves the next incomplete phase and runs with
  `--skip-already-installed`.
- Add `state history` with recent run summaries, durations, failures, and restart checkpoints.
- Add `retry --failed` for failed items once item-level failure state is persisted.

### Clearer Planning

- Add `plan --format table|tree|json|yaml`.
- Add `plan --show-commands` for package, Flatpak, shell, binary, and tooling actions.
- Add `plan --diff-state` to show what changed since the last successful phase fingerprint.
- Add `graph --format dot|mermaid` to visualize the phase DAG.
- Add `explain --item NAME` to show why an item will run or skip.

### More Useful Status

- Turn `status` into a drift report with categories:
  - configured and installed
  - configured and missing
  - state-only
  - failed last run
  - unknown probe
  - version drift
- Add filters: `--summary`, `--missing`, `--failed`, `--state-only`, `--json`.
- Include package manager and module name in status output, not only item keys.
- Compare current config fingerprints to stored phase fingerprints.

### Stronger Validation

- Add `ConfigValidator` returning a `ValidationReport` with multiple path-aware errors.
- Add `validate --strict` for warnings as failures.
- Add `validate --format json` for CI and editor integrations.
- Detect duplicate package names within and across modules.
- Detect unsupported package managers for the declared OS target.
- Warn when compiled binaries lack checksums.
- Warn when `shell-command` entries contain likely personal defaults.
- Validate that phase dependencies are acyclic before execution and plan output.

## CLI Experience Ideas

- Add shell completions: `fluxion completion bash|zsh|fish`.
- Add `--color always|auto|never`.
- Add `--quiet` for scripting and `--verbose` for command details.
- Add `--log-file PATH` with redaction of sudo/password prompts.
- Add `--format json` to all read-only commands.
- Add `--profile` consistently to read-only commands where state matters.
- Add `doctor --fix` for safe repairs such as creating the state directory or suggesting Flatpak
  remote commands.

## Config Authoring

- Add `include` support for shared base profiles and host overlays.
- Add variables for common values such as user home, architecture, and OS release.
- Add conditionals for OS/package-manager-specific modules.
- Add `enabled: false` for temporarily disabled jobs/modules.
- Add `tags` and `run --tag TAG` for targeted bootstrap slices.
- Add comments to generated configs explaining each preset section.
- Add `fluxion import packages --from-host` to draft a profile from installed packages.
- Add `fluxion snapshot` to capture current package, Flatpak, shell, and binary state.

## Module And Executor Coverage

- Finish extracting each module type into a `ModuleExecutor`.
- Make `BootstrapOrchestratorImpl` only coordinate phases, dependencies, state, and events.
- Add command previews for every module type.
- Add probes for Dotbot, shell reload, Oh My Zsh, toolchains, and Nerd Fonts where feasible.
- Add package-manager-specific doctor checks for repository availability.
- Add package manager update/refresh steps as explicit modules instead of shell commands.
- Add uninstall/rollback planning as a future non-default capability.

## TUI Improvements

- Run orchestration on a virtual thread and render from an event queue.
- Add panes for phase DAG, current command, recent log, and failures.
- Add keyboard actions: pause after current item, open logs, retry failed item, quit after phase.
- Add progress counters by phase, module, and item.
- Add a restart checkpoint screen with the exact resume command.
- Add a read-only TUI mode for `plan` and `doctor`.

## State And Auditability

- Version the state schema with migration tests.
- Persist failed item records with exit code, error message, and timestamp.
- Persist phase blocked/interrupted records with reason.
- Add a run ID to all state entries from the same invocation.
- Add `state export --format json`.
- Add `state doctor` to detect corrupt, stale, or legacy state.
- Add checksums for downloaded artifacts and state references to installed binary versions.

## Security And Supply Chain

- Require or strongly warn for checksums on compiled binaries.
- Support detached signature verification for downloaded binaries.
- Add allowlists for domains used by compiled-binary modules.
- Redact secrets in shell output, logs, state, and TUI events.
- Add CodeQL and dependency review gates to release branches.
- Add SBOM generation for release artifacts.
- Add checksum verification of release archives in docs.

## Build, CI, And Release

- Add a format/lint gate to CI before tests.
- Expand native CI smoke tests to include `--version`, `generate`, `doctor`, and config validation.
- Package docs and examples inside release archives.
- Publish a generated command reference artifact from Picocli help.
- Wire CLI version from release metadata instead of a hard-coded string.
- Add Dependabot or a documented cadence for Mill/Maven dependencies.
- Add integration tests that invoke the assembled JAR with temp `HOME`.
- Add native-image metadata regression tests for every Picocli command.

## Documentation Improvements

- Add screenshots or terminal captures for `plan`, `doctor`, and restart resume output.
- Add a quickstart that starts from `generate`, not from a large sample config.
- Add a troubleshooting guide for package-manager failures, sudo, Flatpak, and native-image issues.
- Add a config cookbook:
  - Fedora workstation
  - Arch workstation with AUR
  - openSUSE workstation
  - dotfiles-only
  - CLI-only server bootstrap
- Add a glossary: job, phase, module, item, probe, state, fingerprint, restart checkpoint.
- Add an architecture decision record folder for major choices.
- Add a compatibility matrix for distributions, package managers, shells, and tested GraalVM
  versions.

## Creative Later Ideas

- `fluxion marketplace` for curated profile snippets.
- `fluxion profile diff old.yaml new.yaml` for reviewing bootstrap changes.
- `fluxion explain --phase NAME` for phase dependency and blocking reasons.
- `fluxion sandbox` to run harmless checks in a temporary container when available.
- `fluxion daemon` is intentionally not recommended now, but a future watch mode could monitor
  drift without mutating the system.
- Signed team config bundles for shared workstation standards.
- HTML report export after a run for onboarding or compliance records.

## Promotion Criteria

Move an item from this backlog into `TODO.md` when it has:

- a clear user workflow,
- bounded implementation scope,
- tests that can run in CI without mutating the host,
- native-image implications understood,
- documentation touchpoints identified.

