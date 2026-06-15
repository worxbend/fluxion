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

## Implemented Since This Roadmap Was Written

- User-facing product name is `fluxion`; Java packages remain `dev.sysboot`.
- State path is centralized on `~/.local/share/fluxion` with legacy `sysboot` state awareness.
- Shipped configs use placeholders instead of personal Git identity defaults.
- Package probes are package-manager-aware through `ModuleItem`.
- `run --phase` and `run --from-phase` validate missing phases.
- Config parser and state persistence records use boundary-specific package/class names instead of
  generic DTO naming.
- Phase completion is guarded by stored phase fingerprints.
- Skip state refreshes after successful item writes during a run.
- State item metadata uses `Optional` rather than nullable version/checksum fields.
- Plain CLI restart checkpoints print resume guidance, and `status --resume-command` is available.
- `state show -c <config>` reports the next incomplete phase.
- `generate` creates starter configs with `minimal`, `developer`, `desktop`, and `dotfiles`
  presets.
- `doctor` performs host readiness checks for a profile.
- `validate` now uses structured diagnostics with path-aware issues, JSON output, strict warning
  failure mode, package-manager/OS compatibility checks, duplicate package warnings, and missing
  compiled-binary checksum warnings.
- `plan`, `list`, `status`, and `state show` support JSON output for shell automation.
- CI runs the Java format check before tests.
- CI and release native smoke tests cover `--help`, `--version`, and config validation.
- Native release archives include the binary, root README, docs, and example configs.
- The Mill/Maven dependency update cadence is documented in `sysboot/docs/dependency-updates.md`.
- `graph` renders the phase DAG as Mermaid, DOT, or JSON.
- `lint` reports advisory profile quality and safety findings with text/JSON output.
- `lint` flags `curl | bash` installers and repository setup hidden inside shell commands.
- `status --version-drift` filters directly to version-drifted items.
- `plan --show-commands` displays executor command previews where available.
- `validate` and `doctor` reject unsupported compiled-binary archive formats before installation.
- `plan --format table|tree|json` offers compact, hierarchical, and machine-readable views.
- Integration tests cover malformed YAML and state path/reset behavior.
- Phase state persists failure/blocked reasons and exposes them in `state show --format json`.
- Integration tests cover prompt-logout state and generated resume commands.
- Compiled binaries support HTTPS `checksumUrl` SHA-256 files as an alternative to inline checksums.
- Compiled-binary state records persist source URL, resolved checksum, and detected version when
  available.

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

Status: implemented for JSON output on `validate`, `plan`, `list`, `status`, and `state show`.
YAML output remains a later enhancement if there is real demand for it.

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

Status: implemented with configured-installed, configured-missing, state-only, unknown, and
version-drift classifications plus `--summary`, `--missing`, `--state-only`, and `--failed`
filters.

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

## P4 - Strategic Product Direction

Fluxion should not compete as another dotfile manager. Its strongest product shape is a focused,
local, restart-aware workstation bootstrap runner: Ansible-shaped enough to model a machine, but
small and opinionated enough for one fresh Linux desktop or development laptop.

### 23. Add `fluxion apply` as the safe default run mode

Status: implemented for the CLI boundary: `apply` is the documented primary command, `run` remains
an alias, `apply --dry-run` uses the existing dry-run flow, `--yes` is accepted for unattended
execution, and generated resume commands prefer `apply`.

`run` is accurate but sounds like a script launcher. `apply` makes the user-facing model closer to
"make this machine match the profile" while still keeping the implementation imperative where Linux
requires it.

Implementation:

- Keep `run` as an alias for compatibility.
- Make `apply` the documented primary command.
- Default to showing a compact plan summary before mutation in interactive terminals.
- Add `apply --yes` for unattended setup.
- Add `apply --dry-run` as an alias for the existing dry-run flow.

Tests:

- `apply` and `run` execute the same orchestration path.
- `apply --dry-run` emits no mutating shell commands.
- Help output presents `apply` as the preferred command.

### 24. Add a first-class `diff` command

Status: first slice implemented as a read-only CLI command with text and JSON output, backed by the
existing execution plan, live probes, and state comparison. It reports missing configured items,
state-only entries, unknown probe results, and version drift.

The missing bridge between `plan` and `status` is "what would change on this host right now?".
Ansible has check/diff patterns; Fluxion can make that local and understandable.

Command:

```bash
fluxion diff -c ~/.config/fluxion/workstation.yaml
fluxion diff -c ~/.config/fluxion/workstation.yaml --format json
```

Output categories:

- packages to install
- Flatpaks to install
- shell commands that would run
- binaries missing or version-drifted
- phases skipped by matching fingerprint
- phases rerun because config changed
- state entries no longer present in config

Implementation:

- Build on the canonical `ExecutionPlan`.
- Reuse probes and phase fingerprints.
- Never mutate state.
- Include enough machine-readable detail for CI and dotfile repo review workflows.

### 25. Add a `why`/`explain` command for trust

Status: implemented as `fluxion explain` with `--phase` and `--item` selectors, text/JSON output,
phase dependency context, restart effect, module ownership, current status classification, and
command previews where available.

When bootstrapping a real machine, users need to know why an item is about to run or skip. This is
where Fluxion can feel more predictable than shell-script bootstrap repos.

Command:

```bash
fluxion explain -c config/example-fedora.yaml --phase shell-foundation
fluxion explain -c config/example-fedora.yaml --item zsh
```

Explain:

- phase dependency path
- matched or changed fingerprint
- state hit, probe hit, or no skip reason
- exact executor selected
- command preview
- restart policy effect

### 26. Add workstation snapshot/import workflows

Status: snapshot, package import, and Flatpak import slices are implemented. `fluxion snapshot --output
snapshot.json` produces review-required JSON for OS metadata, detected package managers, package
inventory when available, Flatpak apps/remotes, default shell, and common toolchain presence.
`fluxion import packages --from-host --output packages.yaml` now generates a review-required YAML
packages fragment from RPM, Pacman, or Dpkg host databases. `fluxion import flatpaks --from-host
--output flatpaks.yaml` generates a review-required YAML Flatpak fragment from installed app IDs.

Fluxion should help users turn a hand-built machine into a reproducible profile. This is a clear
differentiator from dotfile tools and a lighter-weight alternative to writing Ansible roles from
scratch.

Commands:

```bash
fluxion snapshot --output snapshot.json
fluxion import packages --from-host --output packages.yaml
fluxion import flatpaks --from-host --output flatpaks.yaml
```

Scope:

- installed package lists by detected package manager
- Flatpak app IDs and remotes
- default shell
- common toolchain presence: Rustup, SDKMAN, Juliaup
- selected binary versions when version commands are configured

Guardrails:

- Never include secrets or shell history.
- Mark generated content as review-required.
- Prefer profile fragments over a giant generated config.

### 27. Add host overlays and profile composition

Real workstation configs need "base developer profile plus this machine's hardware/role". Avoid
turning the main YAML into a templating language; use explicit composition instead.

Config shape:

```yaml
includes:
  - profiles/base-linux.yaml
  - profiles/developer.yaml
  - hosts/thinkpad.yaml
```

Rules:

- Includes are resolved relative to the including file.
- Jobs merge by name only when explicitly marked mergeable.
- Duplicate job/module names fail by default.
- `fluxion plan` shows the source file for each job and step.
- Native-image resource and reflection implications are tested.

### 28. Add `assert` and `manual` step types

Not every workstation prerequisite should be mutated automatically. Fluxion can model human-safe
checkpoints instead of hiding them in shell commands.

`assert` examples:

```yaml
- type: assert
  name: secure-boot-disabled
  command: "mokutil --sb-state | grep -qi disabled"
  message: "Disable Secure Boot before installing this graphics stack."
```

`manual` examples:

```yaml
- type: manual
  name: github-login
  message: "Run `gh auth login`, then continue."
  probeCommand: "gh auth status"
```

Behavior:

- `assert` fails the phase when the condition is false.
- `manual` pauses in TUI mode and prints instructions in CLI mode.
- Both participate in plan, status, state, and resume.

### 29. Add package repository modules

Current examples use `shell-command` for repository setup. That works, but it is exactly where
bootstrap scripts become hard to audit. Repositories should become first-class, OS-specific modules.

Module ideas:

- `rpm-repository`
- `apt-repository`
- `pacman-repository`
- `flatpak-remote`

Capabilities:

- add repository
- import signing key
- verify expected key fingerprint where available
- probe existing repository state
- preview commands in `plan --show-commands`
- warn when a repository lacks signing verification

### 30. Add artifact provenance for downloaded binaries

Compiled binary support is useful but supply-chain-sensitive. Fluxion should make safe downloads
the default.

Implementation:

- Treat missing checksums as errors in `validate --strict`.
- Support SHA-256 checksum URLs, inline checksums, and detached signatures.
  - Status: inline checksums and HTTPS `checksumUrl` files are implemented; detached signatures
    remain open.
- Persist installed artifact URL, checksum, and detected version in state.
  - Status: implemented for compiled-binary successes when checksum/version data is available.
- Add `doctor` checks for unsupported archive formats and non-HTTPS URLs.
  - Status: unsupported archive formats and compiled-binary URL/checksum URL reachability are
    implemented; non-HTTPS URLs are rejected while parsing.

### 31. Add run reports

After a long bootstrap, the user should get a useful artifact: what changed, what failed, what was
skipped, and how to resume. This is valuable for personal audit, support, and team onboarding.

Command:

```bash
fluxion report last --format markdown
fluxion report last --format html
```

Report contents:

- profile and config path
- host OS and detected package managers
- run ID, start/end time, duration
- phase timeline
- installed/skipped/failed items
- restart checkpoints
- exact resume command if incomplete
- redacted command output snippets for failures

### 32. Add profile quality scoring

Status: first slice implemented as `fluxion lint`, with text/JSON output and advisory checks for
compiled-binary checksums/versions, shell-command probes, destructive shell commands, `curl | sh` /
`curl | bash`, repository setup hidden inside shell commands, and embedded `sudo`.

Fluxion can guide users toward safer bootstrap profiles without being a policy engine.

Command:

```bash
fluxion lint -c config/example-fedora.yaml
```

Score dimensions:

- reproducibility: pinned versions, checksums, explicit remotes
- recoverability: restart policies, probes, continue-on-error choices
- safety: sudo usage, unsigned downloads, destructive shell commands
- portability: OS-specific steps isolated into overlays
- observability: named modules, probe commands, command previews

`validate` should stay correctness-focused; `lint` can be advisory and opinionated.

## P5 - Build, CI, Release

### 33. Add CI format checking

Status: implemented.

`just format-check` exists but should run in CI.

Implementation:

- Update `.github/workflows/ci.yml`.
- Fail fast before test/native jobs.

### 34. Expand native smoke tests in CI

Status: implemented for `--help`, `--version`, and native config validation.

Native CI should run more than `--help`.

Add:

```bash
sysboot/out/cli/nativeImage.dest/native-executable --version
sysboot/out/cli/nativeImage.dest/native-executable validate --no-tui -c sysboot/config/example-fedora.yaml
```

### 35. Populate integration tests

Status: initial implementation added for read-only CLI commands and JSON formats.

The `integration-tests` module exists but has no useful test source.

Good first tests:

- CLI validate/list/plan/status with temp HOME
- state path/reset behavior
- malformed YAML exit codes
- prompt-logout resume state

### 36. Package release archives with docs/examples

Status: implemented for native archives in CI and release workflows.

Release archives should include more than the binary.

Include:

- `README.md`
- `docs/config-schema.md`
- `config/example-*.yaml`
- license if added

### 37. Make versioning release-driven

Status: implemented with CLI version metadata in `VersionProvider` and a release workflow check
that the release tag matches that metadata.

CLI version and README examples disagree.

Implementation:

- Wire version from build/release metadata.
- Keep README asset names aligned with actual release workflow.
- Add a smoke assertion for `--version`.

### 38. Add dependency update coverage

Status: documented cadence added because this Mill YAML project does not expose Maven POM files for
Dependabot.

Dependabot currently covers GitHub Actions only.

Implementation:

- Add update coverage or a documented cadence for Maven coordinates in
  `sysboot/**/package.mill.yaml`.

### 39. Gate native-image metadata

Status: implemented with `just native-metadata-check`, CI/release metadata gates, and native smoke
validation for every shipped config.

Manual GraalVM config must stay correct.

Implementation:

- Native build in release precheck.
- Validate every shipped config with the native executable.
- Update `graal/*.json` whenever DTOs, Picocli commands, or resources change.

## Later Ideas

See `sysboot/docs/enhancements.md` for a broader backlog of product, CLI, state, security, TUI,
release, and documentation ideas. Promote items from that document into this roadmap when they are
ready for implementation.

- `fluxion explain -c config.yaml --item git` to show why an item will run or skip.
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
