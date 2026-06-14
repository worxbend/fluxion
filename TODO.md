# TODO.md

Consolidated roadmap for Fluxion, the active Java/Mill bootstrapper under `sysboot/`.
This replaces the older leftover list with a forward implementation plan from repo inspection
and parallel agent analysis.

## Current Baseline

- Active codebase: `sysboot/`
- Build root: `cd sysboot`
- Primary README should live at repository root: `README.md`
- Recent verification reported as passing:
  - `./mill __.test`
  - shipped config validation
  - `./mill cli.assembly`
  - JAR `--help` / `--version`
  - `./mill cli.nativeImage`
  - native binary `--help` / `--version` / `validate`

## P0 - Trust And Correctness

### 1. Decide and enforce one product name

Current state mixes `sysboot` and `fluxion` in code, docs, state paths, and release docs.

Implementation:

- Pick `fluxion` as the user-facing command and product name, unless a deliberate rename back to
  `sysboot` is chosen.
- Keep Java package names as `dev.sysboot` for now to avoid broad churn.
- Update command names/help, docs, release archive names, state directory, and examples.
- Add tests that assert help text, version text, and state path use the selected product name.

Touchpoints:

- `sysboot/cli/src/dev/sysboot/cli/SysbootCommand.java`
- `sysboot/cli/src/dev/sysboot/cli/StateCommand.java`
- `sysboot/executor/src/dev/sysboot/executor/JsonStateRepository.java`
- `sysboot/docs/release.md`
- `sysboot/docs/native-image.md`
- `README.md`

fluxion is my choice.

### 2. Fix state path mismatch - we agree to use fluxion

`JsonStateRepository` writes `~/.local/share/sysboot`, while CLI state commands use
`~/.local/share/fluxion`.

Implementation:

- Add a single state path abstraction or repository path API.
- Route `state path`, `state reset`, repository load/save, and tests through it.
- Decide migration behavior from old `sysboot` state files to `fluxion`.
- Do not silently delete or ignore old state.

Tests:

- `state path` equals the repository path.
- `state reset` deletes the file written by `JsonStateRepository`.
- Missing state returns empty.
- Corrupt state reports an actionable read error.

### 3. Remove personal defaults from shipped configs

`config/example-fedora.yaml` contains a real email/name in Git defaults.

Implementation:

- Replace personal values with placeholders or remove those commands from examples.
- Keep realistic structure without shipping private identity data.
- Validate every shipped config after edits.

### 4. Make package probes package-manager-aware

`InstalledProbeRegistry` routes by `ItemType`, so package probes can use the wrong manager
depending on registration order.

Implementation:

- Introduce a richer probe target, for example `ModuleItem` with `ItemType` and
  `PackageManagerKind`.
- Route package probes by package manager:
  - DNF/Zypper: RPM query
  - Apt: dpkg query
  - Pacman/Paru/Yay: pacman query
- Reuse the same routing from `run`, `plan`, `status`, and skip evaluation.

Tests:

- APT packages call the APT probe.
- Pacman/Paru/Yay packages call the pacman probe.
- DNF/Zypper packages call the RPM probe.

### 5. Make phase filtering safe

`run --phase` and `run --from-phase` should fail when the phase does not exist instead of
surprising the user.

Implementation:

- Validate requested phase names before execution.
- If the filter selects zero phases, return configuration error and list valid phases.
- Add tests for missing phase names and successful partial execution.

## P1 - Architecture Foundation

### 6. Define package ownership rules

Use package names to express architectural ownership, not technical dumping grounds. Packages should
answer which layer owns the type and which capability it belongs to.

Target direction:

- Prefer feature/capability first, layer second.
- Keep package names singular, lowercase, short, and stable.
- Avoid package names like `dto`, `model`, `common`, `helper`, `util`, `manager`, and `processor`.
- Allow shared packages only for small, truly cross-cutting primitives.
- Keep tests mirroring production package structure.

Suggested long-term template for larger capabilities:

```text
dev.sysboot.<capability>
├── domain
│   ├── model
│   ├── value
│   ├── event
│   ├── policy
│   ├── service
│   └── exception
├── application
│   ├── command
│   ├── query
│   ├── result
│   ├── port
│   │   ├── in
│   │   └── out
│   └── service
├── adapter
│   ├── in
│   │   ├── cli
│   │   └── tui
│   └── out
│       ├── persistence
│       └── external
└── config
```

Apply pragmatically to this repo:

- Keep Mill modules as the primary large boundary for now:
  `cli -> app -> tui -> executor -> config-parser -> core`.
- Do not perform a disruptive package rename until the `ModuleItem` and `ModuleExecutor`
  foundations exist.
- When refactoring, move toward capability-owned packages inside modules instead of flat technical
  buckets.
- Keep domain free of JSON, YAML, HTTP, persistence, terminal UI, and external command contracts.
- Put ports in application/domain-owned packages and adapters in infrastructure-owned packages.

Naming matrix:

- API/CLI input: `Request` only when there is a real API boundary.
- Application write input: `Command`.
- Application read input: `Query`.
- Application output: `Result`.
- Domain aggregate/value/event: use the real domain name, with past-tense events.
- Persistence type: `Entity`, `Record`, or `Projection`.
- External contract: include the external system name, for example `StripePaymentRequest`.
- Mapper: include the boundary, for example `ConfigYamlMapper`, `StatePersistenceMapper`.
- Repository port: `StateRepository`.
- Repository adapter: implementation-specific, for example `JsonStateRepository`.

Hard rule:

- Name classes by responsibility.
- Name packages by ownership.
- Name modules by capability.

### 7. Add canonical `ModuleItem`

Item identity is duplicated across orchestration, probing, plan output, dry-run, and status.

Implementation:

- Add `ModuleItem(ModuleName moduleName, String key, String displayName, ItemType type, ...)`
  in `core`.
- Include package manager or module metadata where probing/dry-run needs it.
- Use it in:
  - `BootstrapOrchestratorImpl`
  - `ParallelProbeRunner`
  - `PlanCommand`
  - `StatusCommand`
  - `SkipEvaluator`

### 8. Introduce `ModuleExecutor` and registry

The orchestrator has a large sealed switch and constructs executors in execution paths.

Implementation:

- Add a `ModuleExecutor` port with methods for:
  - `supports(BootstrapModule)`
  - `items(BootstrapModule)`
  - `execute(...)`
  - `dryRun(...)`
- Add `ModuleExecutorRegistry` in `executor`.
- Convert each module type into a module executor adapter.
- Keep `BootstrapOrchestratorImpl` focused on phase flow, events, state, and restart policy.

Suggested first slice:

- Implement `ModuleItem` and executor registry for package modules only.
- Migrate `plan`, `dry-run`, and package probing to it.
- Expand module by module after tests are stable.

### 9. Build a structured `ExecutionPlan`

`plan` and `dry-run` should share one canonical representation.

Implementation:

- Add records for phase, module, item, action, skip reason, restart effect, and command preview.
- Build plans from `PhaseExecutionPlanner`, module executors, state, and probes.
- Add output formats:
  - `table`
  - `tree`
  - `json`
  - `yaml`
- Make `run --dry-run` and `dry-run` delegate to the plan path where practical.

### 10. Make package-manager dry-run commands executor-owned

Generic dry-run command rendering is wrong for some managers.

Implementation:

- Add command preview support to package manager executors.
- Render correct commands for `dnf`, `apt`, `pacman`, `paru`, `yay`, and `zypper`.
- Reuse previews in `plan --show-commands`.

### 11. Split orchestration responsibilities

`BootstrapOrchestratorImpl` currently owns planning, dependency blocking, skip policy, state
writes, restart behavior, event emission, and module dispatch.

Implementation:

- Inject `PhaseExecutionPlanner` instead of constructing it internally.
- Extract small collaborators:
  - `PhaseStateRecorder`
  - `RestartPolicyHandler`
  - `ModuleRunner`
  - `StateBackedSkipPolicy`
- Use existing planner blocked-phase logic instead of ad hoc direct dependency checks.

### 12. Rename DTO packages by boundary and role

Avoid generic `dto` packages as the codebase grows. The current DTOs are not one kind of object:
config parser classes are YAML input contracts, while state DTOs are persistence records.

Implementation:

- Rename `dev.sysboot.config.dto` to a boundary-specific package, for example
  `dev.sysboot.config.yaml.contract` or `dev.sysboot.config.yaml.input`.
- Rename config DTO classes by role where useful:
  - `ConfigDto` -> `ConfigDocument`
  - `PhaseDto` -> `PhaseDocument`
  - `PackagesModuleDto` -> `PackagesModuleDocument`
- Rename `dev.sysboot.executor.dto` to persistence-specific naming, for example
  `dev.sysboot.executor.state.record`.
- Rename state DTO classes by persistence role:
  - `BootstrapStateDto` -> `BootstrapStateRecord`
  - `StateEntryDto` -> `StateEntryRecord`
  - `PhaseStateEntryDto` -> `PhaseStateEntryRecord`
- Keep public/domain classes free of `Dto`, `Data`, `Model`, and vague `Payload` suffixes.
- Use role suffixes consistently:
  - `Command` for application write intent
  - `Query` for application read intent
  - `Result` for application use-case output
  - `Request` / `Response` only for API boundaries
  - `Record` / `Projection` for persistence
  - `Contract` / `Message` for external integration boundaries

Touchpoints:

- `sysboot/config-parser/src/dev/sysboot/config/dto`
- `sysboot/executor/src/dev/sysboot/executor/dto`
- `sysboot/graal/reflect-config.json`
- config parser and state repository tests

## P2 - State, Idempotency, And Resume

### 13. Make phase completion config-aware

A completed phase is currently skipped by name even if its modules/packages changed.

Implementation:

- Store a stable phase fingerprint with `PhaseStateEntry`.
- Include ordered module/item identities and relevant config fields.
- Skip completed phases only when the fingerprint matches.

Tests:

- Completed phase skips when unchanged.
- Completed phase reruns when a package or command changes.

### 14. Refresh skip state during a run

`SkipEvaluator` starts with a state snapshot. Duplicate items later in the same run may not see
newly recorded successes.

Implementation:

- Either load current state through `StateRepository` during evaluation or keep an in-memory run
  state updated after each success.
- Add a duplicate-item test with `--skip-already-installed`.

### 15. Make state non-null and richer

`StateEntry` permits nullable version/checksum fields, and callers pass `null`.

Implementation:

- Replace nullable fields with `Optional<String>` or a small artifact metadata record.
- Add state schema version constants.
- Persist failed/blocked/interrupted phase status with reason and timestamps.
- Add repository methods for `path`, `forgetItem`, `forgetPhase`, and `reset`.

### 16. Improve restart/resume UX

Restart checkpoints exist, but users need clear resume guidance.

Implementation:

- On restart-required events, print the exact command to resume.
- Add `status --resume-command`.
- Show next runnable phase in `state show`.
- Store interruption reason in phase state.

## P3 - CLI And Product Experience

### 17. Add `generate`

Create valid starter configs from the host OS.

Implementation:

- New command: `fluxion generate --os auto --profile NAME --output PATH`.
- Presets: `minimal`, `developer`, `desktop`, `dotfiles`.
- Detect `/etc/os-release` and choose package manager.
- Generate comments or optional steps without personal defaults.

### 18. Add `doctor`

Preflight host readiness before a bootstrap run.

Checks:

- Supported OS and package manager availability
- Flatpak and Flathub setup
- Sudo availability
- Writable state directory
- Config file readability
- Shell paths
- Network access for binary URLs

### 19. Improve validation diagnostics

Validation should report multiple path-aware errors, not stop at the first exception.

Implementation:

- Add `ConfigValidator` returning `ValidationReport`.
- Include paths like `jobs[1].steps[0].packages`.
- Detect duplicate names, missing dependencies, cycles, unknown managers, unsupported OS values,
  empty jobs, and unchecked binary downloads.
- Add `validate --strict` and `validate --format json`.

### 20. Add structured output modes

Make read-only commands scriptable.

Commands:

- `plan`
- `validate`
- `list`
- `status`
- `state show`

Formats:

- `table`
- `json`
- `yaml`

### 21. Turn status into a drift report

Compare config, state, and live machine.

Statuses:

- configured and installed
- configured and missing
- state-only
- unknown
- version drift

Add filters:

- `--summary`
- `--missing`
- `--failed`
- `--state-only`

### 22. Make the TUI live

The current TUI path should render continuously during execution.

Implementation:

- Run orchestration on a virtual thread.
- Drain execution events on a tick loop.
- Add panes for phase DAG/progress, current command, recent log, and failures.
- Add keys for pause-after-current-item, logs, retry command, and quit-after-phase.

## P4 - Build, CI, Release

### 23. Add CI format checking

`just format-check` exists but should run in CI.

Implementation:

- Update `.github/workflows/ci.yml`.
- Fail fast before test/native jobs.

### 24. Expand native smoke tests in CI

Native CI should run more than `--help`.

Add:

```bash
sysboot/out/cli/nativeImage.dest/native-executable --version
sysboot/out/cli/nativeImage.dest/native-executable validate --no-tui -c sysboot/config/example-fedora.yaml
```

### 25. Populate integration tests

The `integration-tests` module exists but has no useful test source.

Good first tests:

- CLI validate/list/plan/status with temp HOME
- state path/reset behavior
- malformed YAML exit codes
- prompt-logout resume state

### 26. Package release archives with docs/examples

Release archives should include more than the binary.

Include:

- `README.md`
- `docs/config-schema.md`
- `config/example-*.yaml`
- license if added

### 27. Make versioning release-driven

CLI version and README examples disagree.

Implementation:

- Wire version from build/release metadata.
- Keep README asset names aligned with actual release workflow.
- Add a smoke assertion for `--version`.

### 28. Add dependency update coverage

Dependabot currently covers GitHub Actions only.

Implementation:

- Add update coverage or a documented cadence for Maven coordinates in
  `sysboot/**/package.mill.yaml`.

### 29. Gate native-image metadata

Manual GraalVM config must stay correct.

Implementation:

- Native build in release precheck.
- Validate every shipped config with the native executable.
- Update `graal/*.json` whenever DTOs, Picocli commands, or resources change.

## Later Ideas

- `fluxion explain -c config.yaml --item git` to show why an item will run or skip.
- `fluxion graph` to output a phase DAG in DOT/Mermaid.
- `fluxion import packages --from-host` to produce a draft config from installed packages.
- `fluxion snapshot` to record host state before a run.
- Config includes with profiles, e.g. base developer profile plus host-specific overlay.
- Signed config bundles for team workstation bootstrap.

## Standard Verification

From `sysboot/`:

```bash
./mill __.test
./mill cli.assembly
java -jar out/cli/assembly.dest/out.jar --help
java -jar out/cli/assembly.dest/out.jar --version
for f in config/*.yaml; do java -jar out/cli/assembly.dest/out.jar validate --no-tui -c "$f"; done
```

Native release gate:

```bash
./mill cli.nativeImage
./out/cli/nativeImage.dest/native-executable --help
./out/cli/nativeImage.dest/native-executable --version
for f in config/*.yaml; do ./out/cli/nativeImage.dest/native-executable validate --no-tui -c "$f"; done
```
