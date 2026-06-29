# PLAN.md

Implementation roadmap for the active repository.

Last refreshed: 2026-06-29

## Repository Baseline

The active codebase is `sysboot/`, a Java 25 Mill project that builds the user-facing
`fluxion` workstation bootstrapper. Java packages still use `dev.sysboot` to avoid broad
package churn.

Build and validation commands run from `sysboot/`:

```bash
./mill __.test
./mill core.test
./mill config-parser.test
./mill executor.test
./mill cli.assembly
./mill cli.nativeImage
```

Current module direction is strict and must be preserved:

```text
cli -> app -> tui -> executor -> config-parser -> core
```

Current config shape is documented in `sysboot/docs/config-schema.md`:

```yaml
profile: fedora-workstation
os:
  type: fedora
  release: "44"
jobs:
  - name: system-foundation
    dependsOn: []
    restartPolicy:
      type: none
    steps:
      - type: packages
        name: core-cli
        packageManager: dnf
        packages: [git, curl]
```

Implemented capabilities already include:

- Java 25 + Mill 1.1.6 with module-local `package.mill.yaml` files.
- Picocli command surface: `apply`, `run`, `dry-run`, `validate`, `lint`, `list`,
  `plan`, `graph`, `diff`, `explain`, `status`, `state`, `generate`, `snapshot`,
  `import`, and `doctor`.
- Phase/job DAG planning with dependency sorting and cycle validation.
- Restart checkpoints through `RestartPolicy`.
- State under `~/.local/share/fluxion`, including phase fingerprints.
- Package managers: `dnf`, `pacman`, `paru`, `yay`, `apt`, `zypper`, and Flatpak.
- Step types: `packages`, `flatpak`, `flatpak-remote`, `apt-repository`,
  `rpm-repository`, `pacman-repository`, `shell-script`, `shell-command`,
  `compiled-binary`, `dotbot`, `oh-my-zsh`, `nerd-fonts`, `toolchain`,
  `default-shell`, `shell-reload`, `assert`, and `manual`.
- TUI selection/execution path plus plain `--no-tui` mode.
- GraalVM native-image metadata under `sysboot/graal/`.

## Desired Direction From The Input

The pasted input describes a Kubernetes-style manifest:

```yaml
apiVersion: initkit.io/v1alpha1
kind: WorkstationProfile
metadata:
  name: developer-workstation
spec:
  target: ...
  policy: ...
  vars: ...
  sources: ...
  plan:
    - name: apt-base-cli
      kind: apt-packages
      when: ...
      spec: ...
```

For this repository, treat that as a product/schema evolution request, not as a
request to replace the existing Java implementation with the historical Scala initkit plan.

The desired behavior to bring forward:

- Kubernetes-style top-level `apiVersion`, `kind`, `metadata`, and `spec`.
- Informational `spec.target.os`; actual execution selection comes from host facts and
  `when` rules.
- Global `spec.policy`.
- Variable interpolation via `spec.vars` and runtime/host variables.
- Package/source setup via `spec.sources`.
- Ordered `spec.plan` entries with exactly one installer `kind`.
- Per-entry `execution` settings.
- Conditional execution through `when`.
- Explicit `interrupt` plan entries that write state and stop cleanly.
- Dry-run previews before package, filesystem, network, or privileged work.
- Plain CLI and TUI modes backed by the same execution services.

Important adaptation: current Fluxion already has job DAGs, state, restart policies, package
executors, compiled binaries, shell tooling, TUI, and native-image support. The plan below layers
the desired manifest shape onto those foundations instead of rebuilding them.

## Do Not Carry Over

The input includes several details from a different Scala/Mill codebase. Do not apply these unless
the user explicitly asks for a language/build migration:

- Do not migrate from Java 25 to Scala 3.
- Do not introduce Ox, sttp, upickle, SnakeYAML Engine, or Scala module layout.
- Do not replace Jackson YAML unless there is a separate parser decision.
- Do not replace existing `jobs`/`steps` support; keep it backward-compatible.
- Do not change the module direction.
- Do not introduce Spring, CDI, Quarkus, runtime DI, service locators, Gradle, Maven, or SBT.

## Pinned Reference Implementation Scan

Reference scanned on 2026-06-29:
`https://github.com/worxbend/binstaller/tree/4cf05d75fd2905af30497e2d1e41c4b5a3416e78`.

Use this commit as a behavioral reference for the WorkstationProfile migration, not as a code or
build-system template. The repository is the older Scala/Mill `initkit` implementation. Fluxion
stays Java 25, Jackson YAML, current Mill YAML modules, and the existing dependency direction.

Important scanned files:

- `docs/config-structure.md` documents the intended manifest shape and field semantics.
- `config/src/initkit/config/Manifest.scala`, `ManifestSpec.scala`, `PlanEntry.scala`,
  `PackageSpec.scala`, `InstallerSpec.scala`, `Sources.scala`, and `Policy.scala` define the
  config contract.
- `ManifestLoader.scala`, `ManifestValidator.scala`, `PackageSpecDecoder.scala`, and
  `InstallerSpecDecoder.scala` show raw-YAML decoding, kind-specific validation, plan-name
  uniqueness checks, supported execution modes, checksum validation, and state-path separation.
- `core/src/initkit/core/ManifestVariableResolver.scala` resolves `${...}` tokens across metadata,
  target, sources, plan entries, raw specs, `when`, and nested `spec.vars`; it reports unresolved
  and cyclic variables with field paths and plan-entry context.
- `host/src/initkit/host/HostDetector.scala` and `HostFacts.scala`, plus
  `core/src/initkit/core/ConditionEvaluator.scala` and `PlanSelector.scala`, implement host facts,
  `when.os`, `when.commandExists`, `--only`, `--skip`, and already-completed filtering.
- `SourceSetup.scala` and `ExecutionWithSourceSetup.scala` generate and run a source-setup prelude
  only when selected package entries need it, before package installation, with dry-run actions and
  failure short-circuiting.
- `ExecutionContracts.scala`, `ExecutionEngine.scala`, and `ExecutionState.scala` model execution
  policy, plan-operation summaries, state identity/fingerprints, per-entry statuses, dry-run as
  non-mutating, and interrupt exit code `75`.
- `CommandContracts.scala` keeps direct argv commands separate from shell commands and redacts
  sensitive arguments, environment values, URLs, tokens, and password-like text.
- `PackageManagerInstallers.scala` generates one command per package item, supports package
  manager actions before installs, and attempts all package commands before reporting aggregate
  failure.
- `ApplyCommand.scala`, `CliLaunchContext.scala`, `CliRendering.scala`, `TuiExecution.scala`, and
  `TuiViewModel.scala` show how CLI/TUI reporting was backed by the same engine results.

Scan-derived implementation guidance for Fluxion:

- Keep DTO decoding and domain mapping separate. Use typed Java records for stable contract fields,
  but preserve raw spec access only where a kind-specific decoder genuinely needs it.
- Preserve direct-command vs shell-command boundaries so dry-run output and redaction can stay
  accurate.
- Treat source setup as a generated prelude, not a normal user plan entry, unless Fluxion's current
  module model makes a typed synthetic phase simpler.
- Keep dry-run non-mutating across source setup, package installs, file writes, downloads, and
  interrupts.
- Record skipped entries and state transitions explicitly enough for CLI, TUI, and resume behavior
  to agree.
- Prefer the old repo's behavior tests as acceptance inspiration, but rewrite them around Fluxion's
  Java modules and existing fake shell/download/sudo boundaries.

## Compatibility Strategy

Support both schemas during the transition:

1. Existing Fluxion schema remains stable:
   `profile`, `os`, `jobs`/`phases`/`modules`.
2. New manifest schema is added as a second config frontend:
   `apiVersion: initkit.io/v1alpha1`, `kind: WorkstationProfile`.
3. `config-parser` maps both frontends into current core execution types where possible.
4. New concepts without existing equivalents get narrow core records and executor support.
5. Documentation makes the preferred schema explicit only after the new manifest path reaches
   feature parity for dry-run, validation, state, and TUI display.

Recommended naming:

- Keep user-facing command/product name `fluxion`.
- Accept `apiVersion: initkit.io/v1alpha1` initially for compatibility with the provided manifest.
- Consider a later `apiVersion: fluxion.dev/v1alpha1` only through an explicit migration plan.

## Current-To-Desired Mapping

| Desired manifest concept | Current Fluxion equivalent | Plan |
|---|---|---|
| `metadata.name` | `profile` | Map to `ProfileName`. |
| `spec.target.os` | `os` | Map supported distribution/release fields; keep target informational for plan selection when `when` exists. |
| `spec.policy.dryRun` | CLI `dry-run` / `apply --dry-run` behavior | Add config-level default, with CLI override taking precedence. |
| `spec.policy.continueOnError` | phase `continueOnModuleError`, package `continueOnError` | Map carefully; keep package item isolation. |
| `spec.policy.requireSudo` | currently executor-specific sudo behavior | Add policy plumbing where generated commands need it. |
| `spec.vars` | not first-class | Add deterministic interpolation before validation/execution. |
| `spec.sources` | typed repository/remote steps | Decode sources and map to source setup modules or prelude operations. |
| `spec.plan[]` | `jobs[].steps[]` plus phases | Initial mapping can put ordered plan entries into a synthetic phase; DAG remains available through existing schema. |
| `plan[].kind` | `steps[].type` plus `packageManager` | Add kind decoder that maps `apt-packages`, `dnf-packages`, etc. to current typed modules. |
| `plan[].execution` | partial support through phases/orchestrator | Preserve in model; implement sequential first, then bounded per-entry parallel where safe. |
| `plan[].when` | limited OS/package compatibility validation and probes | Add host-fact condition evaluator. |
| `interrupt` | `RestartPolicy.prompt-logout` and `manual` | Add explicit interrupt step or map to checkpoint semantics. |
| `commands` | `shell-command` | Extend structure without making unsafe shell behavior implicit. |

## Milestones

### M1 - Planning And Contract Cleanup

Goal: make the roadmap and docs match the current Java repository and the desired manifest
direction.

Progress:

- 2026-06-29: T001 cleaned stale compatibility planning notes in `MEMORY.md` and added an explicit
  schema compatibility section to `sysboot/docs/config-schema.md`. Stable `jobs`/`steps`, legacy
  `phases`/`modules`, and planned experimental `WorkstationProfile` status are now documented.
- 2026-06-29: Scanned the pinned `worxbend/binstaller` reference implementation at commit
  `4cf05d75fd2905af30497e2d1e41c4b5a3416e78` and added plan notes for the actual prior
  WorkstationProfile implementation. The scan covers manifest contracts, validators, variable
  resolution, host facts, source setup, package command generation, state/interrupt behavior,
  command redaction, and CLI/TUI reporting. The result is behavioral guidance only; Fluxion remains
  Java 25 with the existing Mill YAML module layout.

Tasks:

- Replace stale root planning notes with this plan.
- Review `MEMORY.md`, `AGENT_LOG.md`, and root `README.md` for contradictions.
- Confirm docs no longer point future work at a missing `TODO.md`.
- Add an explicit "schema compatibility" section to `sysboot/docs/config-schema.md`.
- Add examples that show old and new schema side by side once the new parser exists.

Acceptance:

- `PLAN.md` reflects the Java 25 Fluxion baseline.
- No plan item asks for Scala/Ox/sttp migration.
- Existing docs still describe current commands correctly.

Validation:

```bash
cd sysboot
./mill __.test
```

### M2 - WorkstationProfile Parser And Validation

Goal: load the Kubernetes-style manifest as a supported input format.

Progress:

- 2026-06-29: T002 added the first WorkstationProfile Jackson DTO surface under
  `config-parser` with Optional-style scalar accessors and empty collection/map defaults. A minimal
  `apiVersion`/`kind`/`metadata.name`/empty `spec` fixture now deserializes directly into
  `WorkstationProfileDocument`, and GraalVM reflection metadata includes the new DTOs. Loader
  routing and mapping remain in T003/T004.
- 2026-06-29: T003 added top-level schema detection in `YamlConfigLoader`. Legacy Fluxion
  `profile`/`os` with `jobs`, `phases`, or `modules` still routes to `ConfigMapper`, while
  `apiVersion: initkit.io/v1alpha1` plus `kind: WorkstationProfile` routes to the new
  WorkstationProfile mapper path. Unknown root schemas now fail with an explicit schema error.
- 2026-06-29: T004 mapped WorkstationProfile `metadata.name` into the loaded profile name and
  `spec.target.os.distribution` plus release/version/codename fields into the existing Fedora,
  Arch, openSUSE, and Debian/Ubuntu-compatible `OsTarget` variants. The manifest mapper currently
  emits an empty synthetic phase until later tasks map `spec.plan` entries into executable modules.
- 2026-06-29: VALIDATION-6 passed the checkpoint gate after T004. `just verify`,
  `./mill cli.assembly`, `./mill cli.nativeImage`, and `just native-smoke` all completed
  successfully. No regressions were found and no code fixes were needed. Remaining risk is the
  planned T005/T007 work: WorkstationProfile contract validation and executable `spec.plan`
  mapping are still incomplete, so currently loaded manifests still produce an empty synthetic
  phase.
- 2026-06-29: T005 added WorkstationProfile contract validation in the parser path. Invalid
  `apiVersion`/`kind`, blank or duplicate `spec.plan[].name`, unsupported plan kinds, empty
  package/app install lists, malformed SHA-256 checksum objects, and `spec.policy.statePath`
  equal to the manifest path now fail with field-path diagnostics. Plan entries are still
  validated only; T007 remains responsible for mapping package plan kinds into executable modules.
- 2026-06-29: T006 checkpoint validation passed after T005. `./mill config-parser.test`,
  `./mill executor.test.testOnly dev.sysboot.executor.ConfigValidatorTest`, and
  `just native-metadata-check` all completed successfully. No parser compatibility regressions or
  missing native metadata registrations were found, so no follow-up fix tasks were added.
- 2026-06-29: T007 mapped WorkstationProfile package plan kinds into executable package and
  Flatpak modules inside the synthetic `manifest-plan` phase. `apt-packages`, `dnf-packages`,
  `pacman-packages`, and `zypper-packages` now create `PackageModule` instances with the matching
  `PackageManagerKind`; `flatpak-packages` creates a `FlatpakModule` using `remote` or `flathub`.
  Focused parser and executor planning tests passed for a manifest containing all supported
  package plan kinds.
- 2026-06-29: T008 added narrow policy default plumbing. `BootstrapConfig` now carries optional
  `BootstrapPolicy` defaults for WorkstationProfile `spec.policy.dryRun`, `continueOnError`, and
  `requireSudo`; CLI/TUI config filtering preserves the policy without changing command-line
  dry-run behavior. Package plan entries use entry `execution.continueOnError` first, then
  `spec.policy.continueOnError`, then the existing package default. Flatpak plan entries remain
  unchanged because the current core module has no continue-on-error field. Focused core, parser,
  and executor tests passed.

Tasks:

- Add DTOs under `config-parser/src/dev/sysboot/config/yaml/contract/`:
  `WorkstationProfileDocument`, `MetadataDocument`, `SpecDocument`, `PolicyDocument`,
  `TargetDocument`, `SourcesDocument`, `PlanEntryDocument`, `ExecutionDocument`,
  and `WhenDocument`.
- Teach `YamlConfigLoader` or a small detector to route by top-level fields:
  existing Fluxion schema vs `apiVersion`/`kind`.
- Validate:
  - `apiVersion == initkit.io/v1alpha1`
  - `kind == WorkstationProfile`
  - `metadata.name` present
  - plan names unique and non-empty
  - each plan entry has one supported `kind`
  - package lists are non-empty
  - state paths are not the manifest path
  - checksums use supported algorithms
- Map supported plan entries to current core modules where possible.
- Add parser fixtures based on the provided manifest, trimmed enough for focused tests.
- Update GraalVM reflect config for new Jackson DTOs.

Acceptance:

- `fluxion validate -c <workstation-profile.yaml> --no-tui` succeeds for a valid manifest.
- Invalid `apiVersion`, `kind`, duplicate plan names, empty installs, and malformed checksums
  produce clear diagnostics with plan-entry names.
- Existing `jobs`/`steps` configs remain valid.

Validation:

```bash
cd sysboot
./mill config-parser.test
./mill executor.test.testOnly dev.sysboot.executor.ConfigValidatorTest
./mill __.test
just native-metadata-check
```

### M3 - Variable Resolution

Goal: support `${name}` interpolation in new manifests without shell expansion.

Progress:

- 2026-06-29: T009 added parser-side WorkstationProfile interpolation before DTO binding and
  validation. Runtime environment values such as `HOME` and `USER` take precedence, followed by
  resolved `spec.vars`, then a small host-fact set (`host.os.name`, `host.os.arch`, `host.user`,
  and `host.home`). Nested `spec.vars` are resolved first; unresolved or cyclic variables fail
  with field-path diagnostics, and plan-scoped errors include the plan-entry name. The resolver
  only replaces `${name}` tokens and leaves `$()`, backticks, globs, `$repo`, `$arch`, and other
  shell text literal. Focused parser tests and a full JVM test rerun passed.
- 2026-06-29: VALIDATION-12 passed the checkpoint gate after T009. `just verify`,
  `./mill cli.assembly`, `./mill cli.nativeImage`, and `just native-smoke` all completed
  successfully. No regressions were found and no in-scope fixes were needed. Remaining risk is in
  pending feature work: fuller host facts and `when` evaluation, source setup, and non-package
  WorkstationProfile plan kinds are still not implemented.

Tasks:

- Add a resolver boundary in `config-parser` or `executor` that runs before mapping to executable
  modules.
- Variable precedence:
  1. runtime environment variables such as `USER` and `HOME`
  2. `spec.vars`
  3. exposed host facts
- Resolve nested variables in `spec.vars` before resolving plan specs.
- Resolve strings in sources, destinations, commands, args, state paths, and config paths.
- Do not evaluate `$()`, backticks, globs, or shell syntax.
- Return path-aware validation issues for unresolved variables.

Acceptance:

- `${HOME}`, `${USER}`, `${binDir}`, and nested variables resolve in the provided manifest shape.
- Unresolved variables fail validation with the field path and plan-entry name.
- Shell command text remains literal except for `${name}` replacement.

Validation:

```bash
cd sysboot
./mill config-parser.test
./mill __.test
```

### M4 - Host Facts And `when`

Goal: skip plan entries based on the actual host, not only the informational target.

Progress:

- 2026-06-29: T011 added a small injectable host-facts boundary in `core` and a Linux adapter in
  `executor`. `LinuxHostFactsProvider` reads `/etc/os-release` through an injectable path, detects
  Linux distribution/version/codename, normalizes architectures such as `x86_64`/`amd64` to
  `amd64` and `aarch64`/`arm64` to `arm64`, and checks command availability from `PATH`. Focused
  tests prove the boundary can be faked and the adapter can be driven with fixture host data.
- 2026-06-29: T012 added WorkstationProfile `when` evaluation during manifest mapping. Scalar
  host fact matches, field-level and top-level `oneOf`, and `commandExists` now select package plan
  entries through the injectable `HostFactsProvider`. `ApplicationContext` wires the Linux adapter
  into `YamlConfigLoader`, while parser tests use fake Debian, Ubuntu, Fedora, Arch, and openSUSE
  facts.
- 2026-06-29: T013 preserved skipped WorkstationProfile plan entries on `BootstrapConfig` with
  stable reasons, exposes them through `ExecutionPlan`, and reports them in plain `plan` output,
  dry-run/apply `--no-tui` event output, and disabled TUI selection rows.
- 2026-06-29: VALIDATION-16 passed the host-filtering/reporting checkpoint. `just verify`,
  `./mill cli.assembly`, `./mill cli.nativeImage`, and `just native-smoke` all completed
  successfully. No regressions were found and no in-scope fixes were needed. Remaining risk is
  limited to later feature work: source setup, package/source ordering, interrupt/resume parity,
  and non-package WorkstationProfile installer kinds.

Tasks:

- Add a small host-facts service in `executor` or a new narrow core port with executor adapter.
- Detect:
  - OS family
  - distribution from `/etc/os-release`
  - version and codename
  - normalized architecture such as `amd64` or `arm64`
  - command availability from `PATH`
- Add `when` evaluation for:
  - scalar match
  - `oneOf`
  - `commandExists`
- Keep evaluation injectable for tests.
- Surface skip reasons in `plan`, `dry-run`, `apply --no-tui`, and TUI selection.

Acceptance:

- Provided Debian/Ubuntu/Fedora/Arch/openSUSE selectors choose the expected entries with fake
  host facts.
- Skipped entries remain visible in planning output with reasons.
- Existing configs without `when` behave unchanged.

Validation:

```bash
cd sysboot
./mill executor.test
./mill cli.test
./mill tui.test
./mill __.test
```

### M5 - Source Setup Prelude

Goal: run `spec.sources` before matching package plan entries.

Progress:

- 2026-06-29: T001 decoded and validated WorkstationProfile `spec.sources.apt`, `dnf`,
  `zypper`, and `flatpak` sections in the parser path. Valid source sections now load alongside
  existing package plan mappings, while invalid source names, required source fields, HTTP(S) URL
  fields, absolute path fields, `gpgCheck`/`gpgKeyUrl`, and SHA-256 checksum objects report
  section-specific field paths. `SourceSpecDocument` now includes Flatpak `system`; existing
  native reflection metadata covers the DTO through declared-field registration. Mapping source
  sections into executable setup remains deferred to T002.

Reference behavior from the pinned `binstaller` scan:

- `SourceSetupGenerator` selected only source sections that matched the active system package
  manager or available Flatpak command.
- Apt generated optional GPG-key setup, source-list writes under `/etc/apt/sources.list.d/`, and
  an `aptUpdateBeforeInstall` flag consumed by package installs.
- DNF generated RPM key imports, release-package installs, `.repo` file writes under
  `/etc/yum.repos.d/`, and custom source commands.
- Zypper generated RPM key imports, `zypper addrepo`, and custom source commands.
- Flatpak generated `flatpak remote-add --if-not-exists` by default.
- Source setup ran as a prelude only when selected package entries needed it; source failure stopped
  package execution before package state was written.

Tasks:

- Decode `spec.sources.apt`, `dnf`, `zypper`, and `flatpak`.
- Map supported sources to existing repository/remote installers where possible:
  - `apt-repository`
  - `rpm-repository`
  - zypper repository command support
  - `flatpak-remote`
- Run only source sections relevant to the host/package manager.
- Make dry-run and `plan --show-commands` show source setup before package operations.
- Keep source setup idempotent where the current installer can probe existing state.

Acceptance:

- Apt source setup previews key/source-list/update actions before apt installs.
- Flatpak remote setup uses idempotent remote-add behavior.
- Source setup failure stops dependent package execution unless policy says otherwise.

Validation:

```bash
cd sysboot
./mill executor.test
./mill cli.test
./mill __.test
```

### M6 - Package Plan Kinds

Goal: support desired package entry kinds without weakening current package-item isolation.

Reference behavior from the pinned `binstaller` scan:

- Package kinds decoded to typed specs for `apt`, `pacman`, `dnf`, `zypper`, `flatpak`, `snap`,
  `aur`, `cargo`, and `sdkman`.
- Apt, pacman, dnf, and zypper accepted optional package-manager actions before item installs.
- Flatpak preserved `remote` and user/system scope.
- Package execution generated one command per package item and attempted every command before
  returning an aggregate failure.
- Command generation preserved direct argv boundaries; shell was used only for cases like SDKMAN
  initialization.

Plan kinds:

- `apt-packages`
- `pacman-packages`
- `dnf-packages`
- `zypper-packages`
- `flatpak-packages`

Also evaluate:

- `snap-packages` as a new package-like executor if Snap support remains desired.
- `aur-packages` as a typed alias over `paru`/`yay` behavior.

Rules:

- Generate one install command per package/app/group item.
- A failed middle item must not prevent later items in the same entry from being attempted.
- The entry reports partial failure after all items are attempted.
- Global/entry policy decides whether top-level execution continues after that aggregate failure.
- Dry-run prints the same itemized command order as apply mode.

Acceptance:

- Tests assert generated argv for every package manager.
- Tests cover a failing middle package and assert later packages are still attempted.
- Sudo behavior follows policy and existing executor conventions.

Validation:

```bash
cd sysboot
./mill executor.test
./mill __.test
```

### M7 - Installer Plan Kinds

Goal: map desired installer kinds onto existing Fluxion modules or add focused new modules.

Reference behavior from the pinned `binstaller` scan:

- `binary-downloads` supported URL downloads, destination/mode, SHA-256/SHA-512 checksums,
  `zip`/`tar.gz`/`tar.xz` archive extraction, selected archive paths, `stripComponents`, and
  optional symlinks.
- `shell-scripts` supported URL script downloads, stdin/file download mode, `creates`, args, cwd,
  env with sensitivity, interactive/unattended mode, cleanup, timeout, allowed exit codes, and
  optional sudo.
- `commands` supported named shell commands, optional sudo, cwd, env with sensitivity, `creates`,
  `unless`, allowed exit codes, confirmation text, timeout, and item-level `when`.
- `file-writes` supported content writes with optional sudo, owner, group, mode, and item-level
  `when`.
- `dotfiles-apply` represented git repository checkout plus dotbot config/preview data.
- `nerd-fonts` represented a tool invocation, generated config, and optional preview command.

Kinds to support first:

- `binary-downloads` -> current `compiled-binary`, extended for multiple items.
- `shell-scripts` -> current `shell-script`, extended for URL downloads if needed.
- `nerd-fonts` -> current `nerd-fonts`.
- `dotfiles-apply` -> current `dotbot`.
- `commands` -> structured `shell-command`.
- `interrupt` -> explicit checkpoint semantics.
- `file-writes` -> add after commands if direct file support is still needed before M9.

Desired extensions:

- Binary downloads:
  - direct binary
  - `tar.gz`
  - selected archive path
  - `stripComponents`
  - checksum validation
  - optional symlink creation
- Shell scripts:
  - URL script download
  - `creates` idempotency
  - env and args
  - dry-run without download or execution
- Commands:
  - item `name`
  - `run`
  - `sudo`
  - `cwd`
  - `env`
  - `creates`
  - `unless`
  - `allowedExitCodes`
  - `confirm`
  - `timeout`
  - item-level `when`

Acceptance:

- The provided manifest can dry-run every non-skipped installer kind.
- Dry-run previews filesystem writes, downloads, symlinks, and commands.
- Apply mode remains behind explicit `apply`; tests use fake shell/download boundaries.

Validation:

```bash
cd sysboot
./mill executor.test
./mill integration-tests.test
./mill __.test
```

### M8 - Interrupt And Resume Parity

Goal: support explicit `kind: interrupt` entries with separate state-file behavior.

Reference behavior from the pinned `binstaller` scan:

- Apply mode wrote state before stopping and used exit code `75` by default.
- Dry-run emitted message and state-write preview actions but did not create or modify state.
- `resumeFrom: current` marked the interrupt entry interrupted and resumed at the same entry.
- `resumeFrom: next` marked the interrupt entry completed and advanced to the next open entry.
- State tracked schema version, manifest identity, manifest fingerprint, timestamps,
  `lastCompleted`, `nextPlanEntry`, and per-entry status/message.

Tasks:

- Add a core/checkpoint model if current `RestartPolicy` cannot express plan-entry interrupts
  cleanly.
- Write state atomically before stopping.
- Mark previous work completed and the interrupt entry interrupted.
- Store the next plan entry when `resumeFrom: next`.
- Print reason, instructions, and resume command in plain CLI output.
- Reflect the checkpoint in TUI output.
- Reject stale state when manifest identity/fingerprint does not match unless reset is requested.

Acceptance:

- The shell logout use case from the input pauses with exit code `75` or a documented pause code.
- A second run with `--state` resumes from the next plan entry.
- Dry-run does not create the state file.

Validation:

```bash
cd sysboot
./mill executor.test
./mill cli.test
./mill tui.test
./mill integration-tests.test
./mill __.test
```

### M9 - Legacy-Parity Typed Kinds

Goal: stop hiding repeated legacy bootstrap behavior inside opaque shell commands where typed
support gives safer validation, preview, state, and TUI reporting.

Add only after the `WorkstationProfile` path is stable:

- `aur-packages`
- `cargo-packages`
- `sdkman-packages`
- `file-writes`

Keep these under structured `commands` unless repeated configs prove a dedicated abstraction is
needed:

- user group changes
- login shell changes
- systemd service enable/start/restart commands
- simple Git clones
- Git config
- time configuration

Acceptance:

- Fedora, Arch/EndeavourOS, openSUSE, and Ubuntu examples cover source setup, package actions,
  shell/tool installers, services, file writes, and post-install configuration.
- Tests assert generated commands for every new typed kind.
- Middle-item failure still attempts later install-like items.

Validation:

```bash
cd sysboot
./mill config-parser.test
./mill executor.test
./mill cli.test
./mill __.test
```

### M10 - CLI/TUI Reporting Polish

Goal: make both output modes explain the new schema clearly.

Progress:

- 2026-06-29: T013 added selected/skipped WorkstationProfile reporting for `plan`, `dry-run`,
  `apply --no-tui --dry-run`, and TUI selection rendering without requiring a real terminal in
  tests. Skipped entries include the manifest plan entry name, kind, and reason.

Plain CLI:

- Show manifest/profile name.
- Show detected host facts.
- Show source setup before package operations.
- Show selected/skipped plan entries with reasons.
- Show dry-run/live mode and state path.
- Show final counts.
- Preserve redacted command rendering for direct argv and shell commands.
- Keep `--no-tui` parseable and free of cursor animation.

TUI:

- Show plan entries in manifest order.
- Disabled skipped entries remain visible with reasons.
- Completed/interrupted state is visible.
- Interrupt entries are visually distinct.
- Dry-run and apply actions use the same execution path as CLI.

Acceptance:

- `plan`, `dry-run`, `apply --no-tui`, and TUI all report the same selected/skipped work.
- Tests cover view-model generation without a real terminal.

Validation:

```bash
cd sysboot
./mill cli.test
./mill tui.test
./mill __.test
```

### M11 - Documentation And Examples

Goal: document the current schema and new manifest schema without confusing users.

Tasks:

- Update `sysboot/README.md`.
- Update root `README.md` if command examples change.
- Update `sysboot/docs/config-schema.md`.
- Add `sysboot/docs/workstation-profile.md` for the Kubernetes-style schema.
- Add example manifests for:
  - Ubuntu
  - Fedora
  - Arch/EndeavourOS
  - openSUSE Tumbleweed
- Document `spec.target.os` as informational.
- Document variable interpolation, dry-run, source setup, interrupt/resume, and safety model.

Acceptance:

- Every documented example validates.
- README examples use `fluxion`, not `initkit`.
- Schema docs clearly identify stable vs experimental fields.

Validation:

```bash
cd sysboot
./mill cli.assembly
java -jar out/cli/assembly.dest/out.jar validate -c config/example-fedora.yaml --no-tui
```

### M12 - Final Validation And Release Readiness

Goal: ensure the new manifest path is safe before presenting it as supported.

Validation checklist:

```bash
cd sysboot
./mill __.test
./mill cli.assembly
./mill cli.nativeImage
just native-metadata-check
```

Smoke checks:

```bash
java -jar out/cli/assembly.dest/out.jar --help
java -jar out/cli/assembly.dest/out.jar validate -c config/example-fedora.yaml --no-tui
java -jar out/cli/assembly.dest/out.jar validate -c config/workstation-profile-example.yaml --no-tui
java -jar out/cli/assembly.dest/out.jar dry-run -c config/workstation-profile-example.yaml --no-tui
./out/cli/nativeImage.dest/native-executable --help
./out/cli/nativeImage.dest/native-executable validate -c config/workstation-profile-example.yaml --no-tui
```

Integrity checks:

- No user secrets in examples.
- No passwords or sudo input in logs or events.
- No unregistered Jackson DTO reflection for native image.
- No dependency cycle.
- No new runtime DI framework.
- No tests invoke real package managers, real sudo, or live network downloads.

## Suggested Immediate Queue

Reference: keep the pinned `binstaller` scan notes as behavioral guidance for the remaining
WorkstationProfile tasks; do not port its Scala/Mill implementation directly.

1. `P001` - Clean stale root planning/docs references.
2. `P002` - Add `WorkstationProfile` DTOs and schema detection.
3. `P003` - Add validation for `apiVersion`, `kind`, metadata, plan names, plan kinds, and
   package item lists.
4. `P004` - Map package plan kinds to existing package modules.
5. `P005` - Add variable interpolation for `spec.vars`.
6. `P006` - Add host facts and `when` evaluation.
7. `P007` - Wire source setup prelude from `spec.sources`.
8. `P008` - Add explicit `interrupt` plan entry behavior.
9. `P009` - Extend binary/download and shell/command specs to cover the provided manifest.
10. `P010` - Add docs and examples for both schemas.
11. `P011` - Run full JVM/native validation.

## Agent Loop Tasks

Strict pending task queue is written to `.agent-loop/tasks.json`; validation defaults are in
`.agent-loop/config.json`. The queue starts with remaining work after the completed parser,
policy, variable, host-facts, `when`, and skipped-reporting milestones recorded above.

1. `T001` - Decode source setup specs (moderate feature).
2. `T002` - Map source setup modules (complex feature).
3. `T003` - Plan source setup prelude (complex feature).
4. `T004` - Checkpoint source prelude (validation).
5. `T005` - Add package manager actions (complex feature).
6. `T006` - Verify package item isolation (moderate fix).
7. `T007` - Checkpoint package behavior (validation).
8. `T008` - Map basic installer kinds (complex feature).
9. `T009` - Extend binary downloads (complex feature).
10. `T010` - Extend scripts and commands (complex feature).
11. `T011` - Checkpoint installer kinds (validation).
12. `T012` - Add interrupt checkpoint model (complex feature).
13. `T013` - Report interrupt resume state (complex feature).
14. `T014` - Checkpoint interrupt resume (validation).
15. `T015` - Add AUR package kind (moderate feature).
16. `T016` - Add cargo package kind (complex feature).
17. `T017` - Add SDKMAN package kind (complex feature).
18. `T018` - Add file write kind (complex feature).
19. `T019` - Checkpoint parity kinds (validation).
20. `T020` - Polish plain CLI reporting (moderate improvement).
21. `T021` - Polish TUI reporting (moderate improvement).
22. `T022` - Checkpoint reporting parity (validation).
23. `T023` - Document WorkstationProfile schema (moderate improvement).
24. `T024` - Add WorkstationProfile examples (moderate feature).
25. `T025` - Validate documentation examples (validation).
26. `T026` - Run final release validation (complex validation).

## Engineering Rules For Every Task

- Read touched modules and tests before editing.
- Keep `core` dependency-free.
- Wire collaborators through constructors and `ApplicationContext`.
- Catch specific exceptions and translate at boundaries.
- Public APIs must not return `null`.
- Return unmodifiable collections.
- Keep package-manager installs itemized.
- Use fake shell/download/sudo boundaries in tests.
- Update docs and GraalVM metadata when YAML DTOs or resources change.
- Preserve existing schema behavior unless an intentional migration is documented.
