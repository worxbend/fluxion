# PLAN-i.md — Fluxion Improvement Plan

Improvement, refactoring and feature plan for the `fluxion` workstation bootstrapper.

- Created: 2026-07-26
- Scope: `sysboot/` (the active Java 25 / Mill codebase behind the `fluxion` CLI)
- Companion documents: `PLAN.md` (the completed `WorkstationProfile` manifest roadmap, M1–M12),
  `sysboot/docs/enhancements.md` (backlog), `AGENTS.md`, `SKILL.md`
- Relationship to `PLAN.md`: `PLAN.md` is **done**. This document is the *next* roadmap and
  supersedes `sysboot/docs/enhancements.md` as the prioritised source of truth.

---

## Implementation Status

Updated: 2026-07-26. Gate is green throughout (`just verify`, `just format-check`, 461 tests).

| Milestone | Status |
| --- | --- |
| **M0 — Correctness and truth** | ✅ **done** |
| **M1 — Streaming, sudo-once, cancellation** | ✅ **done** |
| **M2.1 — ToolBroker** | ✅ **done** |
| **M2.2 — `binstaller-profile` step kind** | ✅ **done** |
| M2.3–M2.5 — `fluxion tools`, worxbend preset library | not started |
| M3–M10 | not started |

Shipped in M1:

- `SudoSession` — one password prompt per run instead of one per privileged command, validated up
  front with `sudo -S -v` (so a typo fails before any work starts), retried three times, kept warm
  by a background timestamp refresh, and zeroed on close. Passwordless hosts are detected with
  `sudo -n -v` and never prompted at all.
- Live command output, carried by a `ScopedValue` so no executor signature changed, surfaced as a
  new `ITEM_OUTPUT` event and rendered by both the CLI (`--verbose`) and the TUI log pane.
- `logback.xml` — diagnostics now go to **stderr** at `WARN`. Logback previously defaulted to DEBUG
  on **stdout**, interleaving framework logging with the execution report and corrupting
  `--format json`. `--verbose` raises the level.
- Ctrl-C is a clean stop: the item in flight finishes, state records the next plan entry, and a
  resume command is printed. Verified end to end — cancel, then `apply --skip-already-installed`
  picks up at exactly the next step. A second Ctrl-C still force-quits.
- Shell command failures now name the command and exit code instead of "One or more shell commands
  failed".

Shipped in M0/M2:

- `ProcessExecution` — output pumped on a virtual thread while the parent waits, timeouts enforced
  via `onExit()` with process-*tree* termination, sub-second precision, bounded head+tail capture,
  stdin closed by default so readers get EOF instead of hanging. 11 tests including a hang, a
  60 000-line child, and a descendant-kill check.
- `ShellRunner.run(..., Consumer<String> outputSink)` — the seam live output will use.
- Sudo password no longer becomes an immutable `String`; encoded from `char[]` and zeroed.
- `PhaseExecutors` — the seven injected-then-ignored executors are now actually used, cached per
  runner. Stubs finally take effect in tests.
- `ToolSpec` / `KnownTools` / `ToolBroker` — PATH → cache → verified download, arch- and OS-aware,
  SHA-256 checked against either `checksums.txt` or a `.sha256` sidecar, ordered candidate asset
  names so a pinned pre-rename release still resolves. `KnownToolReleaseIT` checks every candidate
  against the live GitHub releases.
- `nerd-fonts` fixed (§3.1) and taught `configPath`, so it reads the user's own installer config.
- `dotbot` previews now come from `dotbot plan`/`--dry-run` instead of a hand-written string.
- `binstaller-profile` step kind in both config frontends, with validation, fingerprinting, probe
  targets, doctor checks, `list`/`plan` rendering, native-image metadata, and 14 tests.
- `sysboot/config/worxbend-workstation.yaml` — a profile that drives binstaller, dotbot, and
  nerd-fonts-installer against the configs the user already maintains.

Also found and fixed along the way: `just format-check` was already failing on `HEAD` for 20+ files,
so the CI format gate was red before any of this work.

---

## 0. TL;DR

Fluxion is architecturally clean, well-tested, and does not solve the maintainer's actual problem.

The maintainer bootstraps real machines with
[`w0rxbend/system-bootstrap`](https://github.com/w0rxbend/system-bootstrap): a `Justfile` plus
~20 shell scripts, Dotbot-managed dotfiles, and three purpose-built binaries —
[`binstaller`](https://github.com/worxbend/binstaller),
[`dotbot-go`](https://github.com/worxbend/dotbot-go), and
[`nerd-fonts-installer`](https://github.com/worxbend/nerd-fonts-installer).

Fluxion currently **competes with** those tools (a half-built `compiled-binary` downloader, a
hard-coded Nerd Fonts config writer, a Dotbot shell-out) instead of **orchestrating** them. It also
cannot express roughly a third of what the real scripts actually do (git config, `systemctl`,
`timedatectl`, group membership, git clones, `cargo-binstall`, `zypper addrepo`, GPG key import).
And its `nerd-fonts` executor points at a repository that does not exist, so that step 404s on
first use.

This plan has one organising principle:

> **Fluxion is the orchestrator, not the installer.**
> It owns planning, ordering, host-fact selection, conditionals, dry-run, state, resume, reporting,
> and the terminal experience. Anything that already has a good dedicated tool is delegated to that
> tool over a typed adapter — starting with the maintainer's own `worxbend` tools.

Everything else in this document — the JDK/library modernisation, the functional-core refactor, the
TUI rebuild, the resilience work — serves that principle or the "best in class" bar.

---

## 1. Verified Baseline

Established by reading the tree and running the build on 2026-07-26.

| Fact | Value |
| --- | --- |
| Java sources | 326 files, ~31 265 lines (incl. tests) |
| Build | Mill 1.1.6 YAML build, `temurin:25`, `--release 25`, `-Xlint:all` |
| Modules | `cli → app → tui → executor → config-parser → core` (direction intact) |
| `./mill __.compile` | **passes** |
| `./mill __.test` | **passes** — 60 tests, 4.0 s |
| Native image | `graalvm-community:25`, config under `sysboot/graal/` |
| Runtime deps | picocli 4.7.6, Jackson 2.17.2, commons-compress 1.26.2, slf4j 2.0.13, logback 1.5.6 |
| TUI deps | **none** — hand-rolled ANSI, no JLine/Lanterna despite `MEMORY.md` claiming TamboUI + pty4j |
| Docs | 1 599 lines across 10 files in `sysboot/docs/` |

### 1.1 What actually works today

- **Two config frontends.** Stable `jobs`/`steps` (plus legacy `phases` and flat `modules`), and
  the Kubernetes-style `apiVersion: initkit.io/v1alpha1` / `kind: WorkstationProfile` manifest with
  `spec.vars` interpolation, `spec.sources` prelude, `when` host-fact rules, and ordered `spec.plan`.
- **16 commands**: `apply`, `dry-run`, `validate`, `lint`, `plan`, `graph`, `diff`, `explain`,
  `list`, `status`, `state`, `report`, `generate`, `snapshot`, `import`, `doctor`.
- **Job DAG** with dependency sorting, cycle detection, blocked-phase propagation.
- **Phase fingerprints** and a manifest fingerprint; a completed phase is skipped only when its
  fingerprint still matches, and stale state is rejected with an actionable message
  (`BootstrapOrchestratorImpl.java:780`).
- **Interrupt/resume**: `InterruptModule` writes `nextPlanEntry` to state and exits cleanly.
- **Package managers**: dnf, pacman, paru, yay, apt, zypper, cargo, plus Flatpak and remotes.
- **21 module types** in the `BootstrapModule` sealed hierarchy.
- **Supply chain, partially**: `CompiledBinaryInstaller` verifies SHA-256 and detached signatures,
  and warns when a binary has neither (`CompiledBinaryInstaller.java:110`).
- **Redaction**: `SensitiveTextRedactor` + `CommandTextRedactor` keep sudo input out of logs and
  event output — and there is a test for it.
- **Parallel probing** on virtual threads (`ParallelProbeRunner`).
- **CI**: format gate, native-metadata gate, tests, config validation, JVM + native builds,
  tag-driven release with checksums, CodeQL, Dependabot, Mergify.

This is a genuinely solid foundation. The problems below are about *fit* and *depth*, not about
starting over.

---

## 2. The Adoption Gap

Direct comparison of `system-bootstrap`'s real workflow against what Fluxion can express.

### 2.1 What the real scripts do that Fluxion cannot express at all

| Real work | Where | Fluxion today |
| --- | --- | --- |
| `git config --global user.email/user.name/pull.rebase/...` | `configurations.sh` | ✗ only as opaque `shell-command` |
| `timedatectl set-local-rtc 0` / `set-ntp true` | `configurations.sh`, `opensuse/02-extras.sh` | ✗ |
| `systemctl enable --now <unit>` | several distro scripts | ✗ |
| `usermod -aG docker $USER` + logout checkpoint | implied by the docker/podman steps | ✗ (restart policy exists; the group change does not) |
| `git clone` TPM, three Oh-My-Zsh plugins | `configurations.sh`, `oh-my-zsh-plugins.sh` | ✗ |
| `cargo-binstall eza lsd fd-find …` (17 crates) | `cargo-packages.sh` | partial — `CargoPackageInstaller` exists, `cargo-binstall` does not |
| `rpm --import <key>` then `zypper addrepo` + `modifyrepo --refresh` | `opensuse/02-extras.sh` | partial — `rpm-repository` exists, GPG key import and zypper `addrepo`/`modifyrepo` are not modelled |
| `zypper dup` / `dnf upgrade` / `pacman -Syu` as a first-class step | every `00-system-update.sh` | ✗ only as `shell-command` |
| `opi codecs` (distro-specific helper) | `opensuse/00-system-update.sh` | ✗ |
| SDKMAN candidate installs (8 candidates) | `sdkman-packages.sh` | ✓ `SdkmanModule` exists |
| 12 `curl \| sh` toolchain installers (rustup, nvm, pyenv, poetry, uv, pnpm, starship, julia, dotenvx, miniforge, cargo-binstall, kustomize, helm) | `cli-tools.sh` | partial — `ToolchainModule` covers a fixed `ToolchainKind` set |
| Go tarball → `~/.go` with pinned version | `install_golang.sh` | partial via `compiled-binary` |
| ~70 Flatpak apps grouped by category | `flatpak.sh` | ✓ but with no grouping/tag concept |

### 2.2 What the real scripts delegate to purpose-built tools

This is the important half.

| Tool | Language | Contract | Fluxion today |
| --- | --- | --- | --- |
| `binstaller` | Scala 3 / GraalVM | `plan`, `apply`, `versions`, `lock`; `--config/--state/--reset-state/--only/--skip/--locked/--lock-file`; `apiVersion: binstaller.io/v1alpha1`, `kind: BinaryDistributionProfile`; `policy.mode: developer\|strict`; JSON lock file; sigstore-signed releases | **no integration whatsoever** — Fluxion instead has its own weaker `compiled-binary` downloader |
| `dotbot-go` | Go | `validate -c`, `plan -c --output json`, `--dry-run`, apply; `install.conf.yaml` | shells out, but re-downloads the binary into a temp file on every single run and deletes it (`DotbotExecutor.java:79-124`) |
| `nerd-fonts-installer` | Go | `--config`, `--dry-run`, `--interactive`; YAML `release`/`destination`/`refresh_font_cache`/`families` | **broken** — see §3.1 |
| `dotbot-scala` | Scala 3 | same directive set as `dotbot-go`, native amd64/arm64 | not offered as an alternative backend |

The maintainer's binstaller profile already uses **the same manifest grammar as Fluxion**
(`apiVersion` / `kind` / `metadata` / `spec.policy` / `spec.plan`, `${HOME}` and `${appsDir}`
interpolation). These are sibling schemas. Fluxion referencing a `BinaryDistributionProfile` is a
two-line YAML change for the user and removes an entire duplicated subsystem from Fluxion.

Other `worxbend` Linux tools worth an install-and-configure story (not orchestration): `obsctl`
(Crystal), `obsctl-rs` (Rust, has a stable `--json` envelope), `scenedeck` (Rust/GTK4, on Snap),
`airgradient-cli` (Rust), `airgradient-desktop`, `airgradient-gnome-extension`, `twi` (Go).

### 2.3 Why the gap persists

1. **No migration path.** Adopting Fluxion means hand-writing a several-hundred-line YAML that
   reproduces 20 working scripts. `fluxion import`/`snapshot` exist but do not read a
   `Justfile` + `scripts/` layout.
2. **It duplicates the tools instead of driving them.** A user who already trusts `binstaller`
   gains nothing by re-declaring 20 binaries in a weaker Fluxion schema.
3. **No streaming output.** `zypper dup` takes ten minutes; Fluxion shows nothing until it finishes
   (§3.2). A shell script at least prints.
4. **The sudo experience is worse than `sudo`.** Every privileged command re-prompts (§3.3).
5. **Escape hatches are second-class.** `shell-command` gets no probe, no useful preview, no
   grouping — so the 30 % of work that will always be a shell command feels unsupported.

---

## 3. Defects and Risks Found

Ordered by severity. Each is a concrete, fixable item with a file reference.

### 3.1 `nerd-fonts` step is pinned to an obsolete asset naming scheme

`NerdFontExecutor.java:97` (before this plan's M0 work):

```java
"https://github.com/worxbend/nerd-font-installer/releases/download/%s/nerdfont-install_%s_linux_amd64.tar.gz"
```

Verified against the live releases:

- The old repo name still resolves — GitHub 301-redirects `nerd-font-installer` →
  `nerd-fonts-installer` after the rename. Not a defect.
- The asset name `nerdfont-install_<tag>_<os>_<arch>.tar.gz` is correct for **v1.0.0–v1.0.6** and
  for the floating `latest` tag, which carries both naming schemes.
- It **404s on v1.0.7**, the current release, where both the asset and the binary inside were
  renamed to `nerd-fonts-installer`.

So the shipped default (`v1.0.5`) worked, and anyone pinning the current release got a 404. The
resolver was also hard-coded to `linux-amd64`, so arm64 and macOS were broken outright. The unit
test passed throughout because it stubs `InstallerResolver`.

**Fixed in M0** by `ToolSpec` carrying an ordered list of candidate asset names: the current name is
tried first, the pre-rename name second, so both old and new pins resolve. `KnownToolReleaseIT`
checks every candidate against the live releases so a future rename fails a test rather than a
user's machine.

### 3.2 Process timeouts do not work, and output cannot stream

`DefaultShellRunner.java:29-31`:

```java
String output = new String(process.getInputStream().readAllBytes());   // blocks until EOF
boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
```

`readAllBytes()` blocks until the child closes stdout. A hung child hangs Fluxion **forever**; the
`waitFor` timeout below it is dead code. `PtyShellRunner.java:40-41` has the mirror-image bug —
`waitFor` first, then read — which deadlocks whenever a child fills the ~64 KiB pipe buffer, i.e.
on any verbose package manager. Both also truncate sub-second timeouts via `toSeconds()`.

Because output is only harvested at the end, **live output is architecturally impossible today**.
This is the single biggest UX blocker.

### 3.3 `PtyShellRunner` is not a PTY, and it re-prompts for sudo on every command

Despite the name and the `MEMORY.md` note about pty4j, `PtyShellRunner` is a plain
`ProcessBuilder`. Consequences:

- `sudoPasswordProvider.requestPassword(...)` is called **per sudo invocation**
  (`PtyShellRunner.java:71`). A 40-step profile prompts up to 40 times. There is no `sudo -v`
  keep-alive, no timestamp refresh, no single up-front authorisation.
- Installers that require a TTY (rustup's interactive path, anything calling `isatty`) behave
  differently or fail.
- Progress bars and colour from child processes are lost.
- `PtyShellRunner.java:83` does `new String(pwd).getBytes(UTF_8)` — the password becomes an
  immutable `String` on the heap. The `char[]` is zeroed; the `String` is not.

### 3.4 Validation throws a single joined string instead of structured diagnostics

`WorkstationProfileValidator.java:89`:

```java
throw new IllegalArgumentException(String.join("; ", errors));
```

Errors are collected properly, then flattened into one line with no path, no line/column, no error
code, and no hint. For a 400-line manifest this is close to useless. `ConfigValidator` in
`executor` already returns a proper `ValidationReport` — there are two validation philosophies in
one codebase.

### 3.5 The orchestrator is a 952-line god object with dead fields

`BootstrapOrchestratorImpl` holds 15 executor fields. Seven of them —
`shellScriptExecutor`, `dotbotExecutor`, `defaultShellExecutor`, `ohMyZshExecutor`,
`toolchainExecutor`, `nerdFontExecutor`, `shellReloadExecutor` — are **assigned in the constructor
and never read**, because the switch constructs a fresh instance per item (`:338`, `:345`, `:352`,
`:359`, `:366`, `:373`, `:547`). An eighth executor, `ShellCommandExecutor`, is constructed
per-item at `:380` with no field at all. Injected collaborators are silently discarded, which also
means a test that injects a fake executor for any of those types cannot work.

Two parallel dispatch mechanisms coexist: a `ModuleExecutorRegistry` (used by exactly two module
types) and a 21-arm `switch` (used by the rest), with four arms that `throw IllegalStateException`
as unreachable placeholders. `dryRunModule` duplicates the whole switch a second time.

### 3.6 Two parsers, two validators, two mappers for two schemas

`ConfigMapper` (435 lines) and `WorkstationProfileConfigMapper` (679) + `WorkstationProfileValidator`
(790) + `WorkstationProfileInterpolator` (249) + `WorkstationProfileWhenEvaluator` (241) +
`WorkstationProfileSourceMapper` + `WorkstationProfileSourceValidator`. Every new step kind must be
added in both worlds. `WorkstationProfileValidator` even carries two overlapping literal sets of
plan kinds (`SUPPORTED_PLAN_KINDS` and the `PACKAGE_/APP_/INSTALLER_PLAN_KINDS` triple) that must be
kept in sync by hand.

### 3.7 Everything runs strictly sequentially

Independent jobs in the DAG execute one after another. `ParallelProbeRunner` is the only place that
uses concurrency. Downloading 20 binaries, 70 Flatpaks, and 43 font families serially is minutes of
avoidable wall-clock.

### 3.8 Smaller items

- **No `Clock` injection** — `Instant.now()` is called directly throughout, so durations and
  timestamps are untestable.
- **`ExecutionPausedException` is control flow**, caught-and-ignored in `SysbootTuiApp.java:88`.
- **State is a single JSON blob with a hard-coded `"1.0.0"`** and no migration path; every
  `recordSuccess` does a full read-modify-write of the whole file.
- **`ApplicationContext` has two near-identical factory methods** that differ only in the runner
  and the sudo provider.
- **The TUI redraws by clearing the whole screen** every 100 ms (`SysbootTuiApp.java:118`,
  `[H[2J`) — flicker, no scrollback, no resize handling, no raw-mode keys.
- **No timeout is configurable from YAML** — `CHECK_TIMEOUT` (5 min), `DOTBOT_TIMEOUT` (5 min),
  `FONT_INSTALL_TIMEOUT` (15 min) are all compile-time constants.
- **`DotbotExecutor`/`NerdFontExecutor` hard-code `linux-amd64`** and re-download on every run with
  no checksum verification and no cache — while the upstream `install.sh` scripts verify SHA-256
  *and* sigstore signatures.

---

## 4. Target Architecture

```text
                 ┌──────────────────────────────────────────────┐
   YAML ────────►│  frontend: parse → normalise → validate      │  pure
                 │  (jobs/steps | WorkstationProfile) → IR      │
                 └───────────────────┬──────────────────────────┘
                                     │  ResolvedPlan (immutable)
                 ┌───────────────────▼──────────────────────────┐
   host facts ──►│  planner: when-eval, vars, DAG, skip policy  │  pure
                 └───────────────────┬──────────────────────────┘
                                     │  ExecutionPlan (immutable)
                 ┌───────────────────▼──────────────────────────┐
                 │  engine: schedule, state, events, retry      │  effects at edges
                 └───────┬─────────────────────────┬────────────┘
                         │                         │
             ┌───────────▼─────────┐   ┌───────────▼──────────────┐
             │ StepExecutor SPI    │   │ ToolBroker               │
             │ (packages, flatpak, │   │ ensure/verify/invoke     │
             │  files, systemd, …) │   │ binstaller, dotbot-go,   │
             └─────────────────────┘   │ nerd-fonts-installer, …  │
                                       └──────────────────────────┘
                         │
             ┌───────────▼─────────────────────────────────────┐
             │ event stream → CLI renderer | TUI | JSON | log  │
             └─────────────────────────────────────────────────┘
```

Four rules:

1. **Functional core, imperative shell.** Parsing, validation, interpolation, `when` evaluation,
   DAG planning, skip decisions, fingerprinting, and reporting are pure functions over immutable
   records. Only the engine and executors touch processes, the filesystem, or the network.
2. **One IR, two frontends.** Both YAML dialects normalise into a single `ResolvedPlan`. Every
   downstream concern is written once.
3. **One dispatch mechanism.** A `StepExecutor` SPI with `plan`, `probe`, `execute`, `explain`.
   The `switch` disappears.
4. **Delegate, don't duplicate.** A `ToolBroker` owns discovery, verified installation, version
   pinning, caching, and invocation of external tools.

Module direction is preserved. `core` stays dependency-free.

---

## 5. Platform and Language Modernisation

### 5.1 JDK

| Track | Now | Target |
| --- | --- | --- |
| Language level | 25 (LTS) | **26** for the main build; **27 LTS** as the shipping baseline when it GAs (Sept 2026) |
| CI matrix | 25 only | 25 (compat) + 26 (primary) + 27-EA (early-warning lane) |
| Native image | `graalvm-community:25` | stay on the GraalVM 25.x line until a GraalVM stable build for 26/27 exists; **this is a hard gate on any preview-feature adoption** |

JDK 26 reached GA on 17 March 2026 with ten JEPs. What matters here:

- **JEP 517 — HTTP/3 for the HTTP Client.** Directly useful for the download paths.
- **JEP 516 — AOT object caching with any GC**, on top of JEP 483/514/515 AOT class loading. Cuts
  JVM-mode startup, which matters for `fluxion.jar` users who cannot run the native binary.
- **JEP 525 — Structured Concurrency (6th preview)**, finalising in **JDK 27**. `Joiner.onTimeout()`
  and the tidied `allSuccessfulOrThrow()` / `anySuccessfulOrThrow()` are exactly the shape needed
  for the parallel executor.
- **JEP 526 — Lazy Constants (2nd preview)**: replaces the ad-hoc lazy singletons.
- **JEP 500 — "prepare to make final mean final"**: nothing to do, but audit any reflection.

**Decision:** do not enable preview features in the shipped artifact. Write the parallel engine
against a small internal `Scope` abstraction so that the switch from
`ExecutorService`+virtual threads to `StructuredTaskScope` at JDK 27 is a single-file change.

### 5.2 Language features to adopt deliberately

| Feature | Where it earns its place |
| --- | --- |
| Sealed interfaces + records + exhaustive `switch` | already used well — extend to `PlanKind`, `Diagnostic`, `ToolSpec` |
| **Record patterns / deconstruction** | replaces `instanceof X x` + accessor chains in the orchestrator and reporters |
| **Stream Gatherers** (final since 24) | `windowFixed` for batched package installs, `fold`/`scan` for progress aggregation, custom gatherer for "group consecutive events by module" in the renderers |
| **Scoped Values** (final in 25) | carry run-id, profile, dry-run flag, and the redactor through the call tree instead of threading them as parameters or using `ThreadLocal` |
| **Virtual threads** | already used for probes; extend to the executor and to output-pump threads |
| **Flexible constructor bodies** (final in 25) | validate-before-`this()` in the many validating records |
| **Module import declarations** (final in 25) | trims the 48-line import block at the top of `BootstrapOrchestratorImpl` |
| **Compact source files / instance `main`** | for `scripts/` tooling only; not for production code |
| **`HexFormat`, `Files.mismatch`, `Stream.toList`** | already in use; keep |

Explicitly **not** adopting: the Vector API (incubating, no use case), FFM/`java.lang.foreign` for
PTY work (a maintained JNI binding is lower risk), and preview primitives in patterns.

### 5.3 Functional programming — concretely, not decoratively

The request is "use functional programming stuff". The way to do that in Java without a framework:

1. **A `Result<T, E>` sealed type in `core`**, replacing exception-driven control flow for expected
   failures (validation, probes, tool resolution). Exceptions stay for genuinely exceptional
   conditions. `ExecutionPausedException` becomes `Result.Paused` / an `Outcome` variant.
2. **`Validation<E, T>` (applicative accumulation)** for the config layer — collect *all*
   diagnostics rather than failing on the first, which is what §3.4 needs anyway.
3. **Total functions over sealed hierarchies.** Every `switch` over a sealed type must be
   exhaustive without a `default`, so adding a step kind is a compile error everywhere it matters.
4. **Immutable everything.** No mutable fields outside the engine's scheduler.
   `SkipEvaluator.state` is currently mutable (`refreshState`) — make the evaluator a pure function
   `(State, Item) -> SkipDecision` and let the engine thread the state.
5. **Effects as data.** `plan()` returns a `List<Effect>` (a description of what would happen);
   `execute()` interprets effects. Dry-run then becomes "don't interpret", not a duplicated code
   path — deleting `dryRunModule`'s 100-line mirror switch.
6. **Function-typed ports.** `ShellRunner`, `Clock`, `FileSystem`, `HttpClient` as narrow
   functional interfaces, injected — enabling fast, deterministic, hermetic tests.
7. **No Vavr / functional-java.** They add native-image surface and reflection risk for value the
   language now provides. This is a deliberate decision, recorded in the ADR log.

### 5.4 Library modernisation

| Dependency | Current | Action |
| --- | --- | --- |
| Mill | 1.1.6 | → **1.1.7** now (released 2026-06-21); evaluate 1.2.x after it leaves RC |
| picocli | 4.7.6 | → **4.7.7**; keep — best-in-class for native-image CLIs, and codegen already wired |
| Jackson | 2.17.2 | → latest 2.x; **evaluate Jackson 3.x** separately (breaking, `tools.jackson` namespace) |
| YAML parsing | jackson-dataformat-yaml | **evaluate SnakeYAML Engine** (true YAML 1.2, and — critically — exposes **line/column marks** needed for §3.4 diagnostics). Likely outcome: keep Jackson for binding, add a parallel position index from the raw parse. |
| commons-compress | 1.26.2 | → latest; keep |
| slf4j / logback | 2.0.13 / 1.5.6 | → latest; consider dropping logback for `slf4j-simple` in the native image to shrink the binary |
| **TUI** | none (hand-rolled) | **add JLine 3.30.x** (JLine 4.x also available since June 2026; pick after a native-image spike). Non-negotiable for raw-mode input, resize handling, and partial redraw. |
| **PTY** | none | add a maintained PTY binding (`pty4j`, or JLine's terminal provider) behind a `Pty` port, with a graceful non-PTY fallback |
| **JSON Schema** | none | generate a JSON Schema for both dialects → editor autocomplete via `yaml-language-server`, and `fluxion validate --format json` for CI |
| **ArchUnit** | none | add — enforce the module direction and the "no `null` from public methods" rule as tests |
| **jqwik** | none | add — property tests for the planner, interpolator, and fingerprinting |
| **Testcontainers** | none | add — real Fedora/Arch/openSUSE/Debian containers for integration tests |
| **Error Prone + NullAway** | none | add to the compile gate |
| **CycloneDX** | none | SBOM per release artifact |

---

## 6. Workstream A — First-Class `worxbend` Tool Integration

**This is the highest-value workstream.** It is what makes Fluxion worth adopting.

### 6.1 The `ToolBroker`

A new `executor` component owning the lifecycle of every external tool:

```text
ToolBroker
 ├─ resolve(ToolSpec)     → already on PATH? pinned version? cached under ~/.cache/fluxion/tools?
 ├─ ensure(ToolSpec)      → download release asset, verify SHA-256, verify sigstore when available,
 │                          extract, chmod, cache by (tool, version, os, arch)
 ├─ invoke(ToolInvocation)→ streamed exec through the engine's ShellRunner, structured result
 └─ report(ToolSpec)      → installed version, latest upstream version, update available
```

Requirements:

- **Arch- and OS-aware** (`linux/amd64`, `linux/arm64`, `darwin/*`) — today both executors hard-code
  `linux-amd64`.
- **Verify before execute, always.** Every one of these projects publishes checksums;
  `binstaller` publishes sigstore bundles for every asset. Match the guarantees the upstream
  `install.sh` already gives — never do less.
- **Cache, don't re-download.** `~/.cache/fluxion/tools/<name>/<version>/`. Today `dotbot-go` and
  `nerd-fonts-installer` are downloaded and thrown away on *every run*.
- **Prefer what the user already has.** If `binstaller` is on `PATH` and satisfies the version
  constraint, use it. Never silently shadow a user's installation.
- **`fluxion tools`** command: `list`, `status`, `install <tool>`, `update <tool>`, `which <tool>`,
  `pin <tool> <version>`.

### 6.2 `binstaller` — delegate binary distribution entirely

New plan kind. Fluxion stops pretending to be a binary installer.

```yaml
- name: developer-binaries
  kind: binstaller-profile
  spec:
    config: ~/.config/binstaller/config.yaml   # or an inline BinaryDistributionProfile
    only: [yazi, neovim]                       # optional
    skip: [zig]                                # optional
    locked: true                               # require a compatible lock file
    lockFile: ./binstaller.lock.json
    tool:
      version: v0.2.0                          # pinned; or `latest`
      verify: [sha256, sigstore]
```

Mapping — this is why the integration is clean:

| Fluxion | binstaller |
| --- | --- |
| `fluxion plan` | `binstaller plan --config …` (parsed, folded into Fluxion's plan tree) |
| `fluxion dry-run` | `binstaller plan` (never `apply`) |
| `fluxion apply` | `binstaller apply --config … [--only/--skip] [--locked]` |
| `fluxion diff` / `status` | `binstaller versions` → drift rows |
| `fluxion lock` (new) | `binstaller lock --output …`, recorded in Fluxion state |
| `--reset-state` | `binstaller apply --reset-state` |
| policy `strict` | pass through `policy.mode: strict` and surface violations as Fluxion diagnostics |

Also: **teach `fluxion` to read `BinaryDistributionProfile` natively** as an input to
`fluxion import`, so a user with an existing binstaller config gets a Fluxion profile for free.

**Deprecate `compiled-binary`**: keep it working (it has checksum + signature verification and
tests), mark it "prefer `binstaller-profile`" in docs and in `fluxion lint` output. Do not delete.

### 6.3 `dotbot-go` / `dotbot-scala` — fix and deepen

```yaml
- name: dotfiles
  kind: dotfiles-apply
  spec:
    backend: dotbot-go        # | dotbot-scala | auto
    config: ~/.system-bootstrap/.files/install.conf.yaml
    workingDir: ~/.system-bootstrap/.files
    tool: { version: v0.4.2, verify: [sha256] }
```

- Use `dotbot plan -c <cfg> --output json` for **`fluxion plan` and `fluxion dry-run`** — real
  per-link preview instead of today's opaque `["dotbot-go", version, "--config", path]` string.
- Use `dotbot validate -c <cfg>` inside `fluxion validate` and `fluxion lint`.
- Use `dotbot -c <cfg> --dry-run` as the dry-run apply path.
- **Warn loudly on `force: true` links** in the plan output — the maintainer's dotfiles overwrite
  existing config; that deserves a visible line, not a surprise.
- Support `dotbot-scala` as an interchangeable backend (same directives, native amd64/arm64).
- Cache the binary (§6.1). Verify `dotbot-linux-<arch>.tar.gz.sha256`, which the release publishes.

### 6.4 `nerd-fonts-installer` — fix the 404 and stop rewriting the user's config

```yaml
- name: fonts
  kind: nerd-fonts
  spec:
    config: ~/.config/nerd-fonts-installer/config.yaml   # NEW: use the user's own config
    # or, inline (existing behaviour, kept):
    release: latest
    destination: ~/.local/share/fonts/NerdFonts
    refreshFontCache: true
    families: [JetBrainsMono, Hack, FiraCode]
    tool: { version: v1.0.7, verify: [sha256] }
```

- **Fix `NerdFontExecutor.java:97`** — correct repo, correct asset name, correct binary name,
  arch-aware, checksum-verified against `checksums.txt`.
- **Add an integration test that resolves the real release asset URL** (network-gated, skipped
  offline) so this class of bug cannot regress silently.
- Use `--dry-run` for `fluxion dry-run`; parse its output into per-family items so 43 font families
  show as 43 progress rows, not one opaque step.
- Support `--interactive` passthrough from the TUI ("pick fonts").

### 6.5 The rest of the `worxbend` catalogue

A curated, shipped `sources`/preset library so these are one line each:

| Tool | Install route | Preset kind |
| --- | --- | --- |
| `obsctl` (Crystal) | GitHub release | `binstaller-profile` entry or `binary-downloads` preset |
| `obsctl-rs` (Rust) | GitHub release | as above; its `--json` envelope makes it probeable |
| `scenedeck` (Rust/GTK4) | **Snap** (`snapcraft.io/scenedeck`) or release | needs a new `snap-packages` kind (§7.5) |
| `airgradient-cli` (Rust) | `cargo install` / release | `cargo-packages` |
| `airgradient-desktop` (Rust/GTK4) | release | `binary-downloads` |
| `airgradient-gnome-extension` (JS) | GNOME extension | needs `gnome-extensions` kind (§7.5) |
| `twi` (Go) | GitHub release / container | `binary-downloads` |
| `nerd-fonts-installer` | also on **Snap** | `snap-packages` alternative |

Ship these as `sysboot/presets/worxbend.yaml`, includable via §9.1 `include`. `fluxion generate`
offers "include worxbend tools" as an option.

---

## 7. Workstream B — Capability Parity With Real Scripts

New step kinds, each with `plan` / `probe` / `execute` / `explain`. Every one is drawn from an
actual line in `system-bootstrap`.

### 7.1 `git-config`

```yaml
- name: git-identity
  kind: git-config
  spec:
    scope: global            # global | system | local
    entries:
      user.email: balyszyn@gmail.com
      user.name: w0rxbend
      pull.rebase: "true"
      init.defaultBranch: main
      core.autocrlf: input
```

Probe: `git config --global --get <key>` → idempotent, drift-detectable, and `fluxion diff` can
show `user.name: alice → w0rxbend`. Replaces `configurations.sh`.

### 7.2 `git-repo`

```yaml
- name: zsh-plugins
  kind: git-repo
  spec:
    repos:
      - url: https://github.com/tmux-plugins/tpm
        dest: ~/.tmux/plugins/tpm
      - url: https://github.com/zsh-users/zsh-syntax-highlighting
        dest: ${ZSH_CUSTOM:-~/.oh-my-zsh/custom}/plugins/zsh-syntax-highlighting
        depth: 1
        ref: master
        update: pull          # none | pull | reset-hard
        submodules: true
```

Probe: destination exists and is a git worktree with the right remote. Replaces
`oh-my-zsh-plugins.sh` and the TPM clone.

### 7.3 `systemd-unit`

```yaml
- name: services
  kind: systemd-unit
  spec:
    scope: system            # system | user
    units:
      - { name: docker.service, state: started, enabled: true }
      - { name: sshd.service,   state: stopped, enabled: false, mask: true }
```

Probe: `systemctl is-enabled` / `is-active`. Dry-run shows the exact `systemctl` calls.

### 7.4 `system-setting`

For the `timedatectl` / `hostnamectl` / `localectl` family:

```yaml
- name: clock
  kind: system-setting
  spec:
    timedate: { localRtc: false, ntp: true, timezone: Europe/Warsaw }
    hostname: workstation
    locale: { LANG: en_US.UTF-8 }
```

Probe: `timedatectl show --property=…`. Replaces the tail of `configurations.sh` and
`opensuse/02-extras.sh`.

### 7.5 Additional installer kinds

| Kind | Rationale |
| --- | --- |
| `user-groups` | `usermod -aG docker,libvirt $USER`; **auto-emits a logout checkpoint** when the current session lacks the group. This is exactly the case Fluxion's `RestartPolicy` was built for and cannot currently detect. |
| `gpg-key` | `rpm --import <url>` / `apt-key`-successor keyring drops with fingerprint pinning. Prerequisite for §7.6. |
| `snap-packages` | `scenedeck`, `nerd-fonts-installer` ship on Snap; openSUSE/Fedora users use it |
| `cargo-binstall-packages` | the real `cargo-packages.sh` uses `cargo-binstall`, not `cargo install` — 17 crates, and binstall is ~50× faster |
| `gnome-extensions` | `airgradient-gnome-extension`, plus the Extension-Manager workflow already in the Flatpak list |
| `system-update` | `zypper dup` / `dnf upgrade` / `pacman -Syu` / `apt full-upgrade` as a typed, distro-dispatched step with a long timeout and streamed output — every `00-system-update.sh` starts with this |
| `npm-global` / `pipx` / `go-install` / `uv-tool` | language-ecosystem globals; `pipx`/`uv tool` are the modern replacements for the `curl \| sh` Python installers |
| `appimage` | download + desktop-entry registration |
| `distro-helper` | `opi codecs` on openSUSE, `rpmfusion` on Fedora, `paru`/`yay` bootstrap on Arch |

### 7.6 Repository management, properly

Today: `apt-repository`, `rpm-repository`, `pacman-repository`, and a `ZypperRepositoryInstaller`
that is constructed inline in the orchestrator constructor and has no module type. Unify into one
`repository` kind that dispatches on the host's package manager, and add the missing operations the
real scripts use: `zypper addrepo`, `zypper modifyrepo --refresh`, `--gpg-auto-import-keys`,
`dnf config-manager --add-repo`, COPR enable, PPA add, and Arch `[repo]` blocks with SigLevel.

### 7.7 `shell-command`, promoted to first-class

The escape hatch will always carry ~30 % of a real profile. Make it good:

```yaml
- name: opi-codecs
  kind: commands
  spec:
    commands:
      - run: opi -n codecs
        probe: rpm -q libavcodec-full          # skip when this succeeds
        expectExitCodes: [0, 100]
        timeout: 10m
        retries: { attempts: 3, backoff: exponential }
        env: { LANG: C }
        workingDir: ~/
        stdin: "y\n"
        description: "openSUSE codec bundle via opi"
```

`probe`, `timeout`, `retries`, `expectExitCodes` and `description` are the four things missing today
that force people back to shell scripts.

### 7.8 `fluxion migrate` — the adoption on-ramp

Without this, none of the above gets used.

```bash
fluxion migrate --from ~/.system-bootstrap --out ~/.config/fluxion/workstation.yaml
```

- Parse a `Justfile`: recipes → jobs, recipe order → `dependsOn`.
- Classify each line of each script into a typed step where it can, and into an annotated
  `commands` step (with a `# TODO: classify` marker) where it cannot. **Never silently drop a line.**
- Recognise the tool invocations directly: a call to `binstaller apply --config X` becomes a
  `binstaller-profile` step; `./install` in a Dotbot repo becomes `dotfiles-apply`;
  `nerd-fonts-installer --config X` becomes `nerd-fonts`.
- Detect `sudo`, `chsh`, group changes, and reboot-requiring operations → insert `interrupt` /
  restart checkpoints automatically.
- Emit a **coverage report**: "142 of 168 lines classified; 26 kept as raw commands (listed)".
- Round-trip test in CI against a checked-in fixture copy of the `system-bootstrap` layout.

Complement with `fluxion import --from-host` improvements (explicitly-installed packages only,
Flatpak list, enabled units, `~/.local/bin` contents, git config) so a *working* machine can be
turned into a profile.

---

## 8. Workstream C — Execution Engine and Resilience

### 8.1 Rewrite process execution (fixes §3.2, §3.3)

New `ProcessRunner` port:

```java
sealed interface ProcessRunner {
  ProcessHandle2 start(ProcessSpec spec);      // spec: command, env, cwd, stdin, timeout, pty?
}
record ProcessSpec(List<String> command, Map<String,String> env, Optional<Path> cwd,
                   Optional<byte[]> stdin, Duration timeout, boolean pty, boolean privileged) {}
```

- **Pump stdout/stderr on virtual threads** into a bounded ring buffer *and* the event stream, so
  output is live and the buffer cannot exhaust memory on a chatty child.
- **Enforce timeouts with `Process.onExit()` + `CompletableFuture.orTimeout`**, then
  `destroy()` → grace period → `destroyForcibly()` → kill the process *tree*.
- **Honour sub-second precision**; make every timeout configurable per step and per policy.
- **Real PTY when a step asks for it** (`pty: true`), behind a `Pty` port with a non-PTY fallback so
  the native image degrades gracefully rather than failing.
- **Cancellation**: Ctrl-C sets a flag, the current step is asked to stop, state is flushed, and
  Fluxion exits with a resume hint. Today Ctrl-C leaves orphaned children.

### 8.2 Sudo, once (fixes §3.3)

- Detect up front whether the plan needs privilege (`plan` already knows).
- Prompt **once**, validate with `sudo -v`, then run a keep-alive refresher for the run's duration.
- Support `NOPASSWD` detection, `--sudo-askpass`, and a `--no-sudo` mode that fails the plan
  early with a clear list of the steps that need it, instead of failing halfway through.
- Never materialise the password as a `String`; write the `char[]` through a
  `CharsetEncoder` into a `ByteBuffer` and zero it.
- `fluxion doctor` reports the sudo strategy it will use.

### 8.3 Parallelism (fixes §3.7)

- Execute independent DAG nodes concurrently, bounded by `policy.maxParallel` (default: a small
  number, e.g. 4; `1` restores today's behaviour).
- **Serialise by resource lock, not by accident**: package-manager steps for the same manager take
  an exclusive lock (dnf/zypper/pacman hold their own DB locks anyway and would fail confusingly);
  downloads, Flatpaks, fonts, and git clones run in parallel.
- Structure: `ExecutorService` + virtual threads now; a single-file swap to `StructuredTaskScope`
  when JDK 27 finalises JEP 525.
- The TUI shows N concurrent lanes.

### 8.4 Retry, backoff, and failure semantics

- Per-step `retries: { attempts, backoff: fixed|exponential, retryOn: [network, exit-code:N] }`.
- Classify failures: `Transient` (network, mirror timeout, lock contention) vs `Permanent`
  (package not found, checksum mismatch, permission denied). Only retry `Transient`.
- Mirror/URL fallbacks for downloads.
- **Persist failed items** (exit code, message, timestamp, run id) so `fluxion retry --failed`
  becomes possible — the single most requested recovery feature in `enhancements.md`.

### 8.5 State store, versioned and safe

- Add `schemaVersion` with explicit migrations and migration tests (replace the hard-coded
  `"1.0.0"`).
- Add a **run id** to every entry; add `state history` with per-run duration, failures, and
  checkpoints.
- Append-only run journal alongside the snapshot, so a crash mid-run is recoverable.
- File locking, so two concurrent `fluxion apply` runs cannot interleave writes.
- Automatic pre-run backup + `state restore`.
- `state doctor` for corrupt/stale/legacy detection; `state export --format json`.

### 8.6 Safety rails

- `--confirm-plan` (show plan, require explicit confirmation) and `--assume-yes`.
- `--require-clean-state`.
- **Destructive-operation classification**: any step that overwrites files (Dotbot `force: true`),
  changes the login shell, modifies groups, or touches system config is marked in the plan with a
  distinct colour/symbol and is summarised before execution.
- `--only`, `--skip`, `--tag`, `--since-failure` selectors on `apply`.

---

## 9. Workstream D — Config Authoring and Diagnostics

### 9.1 Composition

- **`include:`** — shared base profiles + host overlays with deterministic merge semantics
  (documented: lists append, maps deep-merge, `!override` to replace).
- **`enabled: false`** on jobs and steps.
- **`tags: [desktop, dev, gaming]`** + `apply --tag dev` — the categories in `flatpak.sh` are
  already tags in comment form.
- **Profiles-within-a-profile** for the laptop/desktop/server split.
- Extend `spec.vars` with `${HOME}`, `${XDG_*}`, `${arch}`, `${distro}`, `${release}`, `${user}`,
  and `${env:NAME}` with a documented precedence order. binstaller already uses `${HOME}` /
  `${appsDir}` — match the syntax exactly.

### 9.2 Diagnostics (fixes §3.4)

Replace the joined-string exception with a structured, rendered diagnostic:

```text
error[F0142]: unknown plan kind `dnf-package`
  ┌─ ~/.config/fluxion/workstation.yaml:47:13
  │
47│       kind: dnf-package
  │             ^^^^^^^^^^^ did you mean `dnf-packages`?
  │
  = help: run `fluxion kinds` to list all 27 supported plan kinds
```

Requires: a `Diagnostic(code, severity, path, span, message, help, related)` record, YAML
line/column marks (SnakeYAML Engine, or a position index built from the raw parse), applicative
accumulation (§5.3), and one renderer shared by `validate`, `lint`, `plan`, and `apply`.
`--format json` emits the same data for CI and editors.

### 9.3 Editor support

- Generate and publish a **JSON Schema** for both dialects from the DTOs at build time
  (`fluxion schema --format json-schema`), checked in and served from the repo so
  `# yaml-language-server: $schema=…` gives autocomplete and inline errors in VS Code/Neovim.
- Schema-generation is CI-verified against the DTOs, so it cannot drift.

### 9.4 CLI ergonomics

- **Shell completions**: `fluxion completion bash|zsh|fish` (picocli generates these; ship them in
  the release tarball and in the Dotbot config).
- `--color auto|always|never`, `--quiet`, `--verbose`, `--log-file PATH` (redacted),
  `--format json` on every read-only command.
- **`fluxion init`** — guided first run: detect distro → `generate` → `doctor` → `plan` → confirm.
- **`fluxion resume`** — resolve the next incomplete phase and continue.
- **`fluxion retry --failed`** (needs §8.4 state).
- **`fluxion kinds`** — list every supported step kind with a one-line example.
- `plan --format table|tree|json|yaml|dot|mermaid`, `plan --diff-state`.
- **`fluxion why <item>`** — "this will run because: not in state, probe says missing, `when` matched
  `distribution == opensuse`".

---

## 10. Workstream E — Terminal Experience

The TUI is currently a full-screen `[H[2J` repaint every 100 ms with line-based input.
It needs a real foundation.

### 10.1 Rebuild on JLine

- Raw mode, key handling, resize (`SIGWINCH`), alternate screen, partial redraw, correct
  wide-character/emoji width. Nerd Font glyphs are the whole point of this user's setup — the TUI
  should use them when the terminal supports them and degrade cleanly when it does not.
- **Verify native-image compatibility first** (spike, timeboxed) — JLine needs
  `--initialize-at-run-time` entries which the build already has stubbed for `org.jline`.

### 10.2 Layout

```text
┌─ fluxion ── workstation-opensuse ── apply ── 00:04:12 ────── 34/120 ──┐
│ ▸ system-foundation      ██████████████████░░░░░░  18/24   running    │
│   dev-toolchain          ░░░░░░░░░░░░░░░░░░░░░░░░   0/31   blocked    │
│   desktop                ░░░░░░░░░░░░░░░░░░░░░░░░   0/65   pending    │
├───────────────────────────────────────────────────────────────────────┤
│ zypper --non-interactive install podman                       [12.3s] │
│   Retrieving package podman-5.4.1-1.x86_64  (18.2 MiB)  ▓▓▓▓▓░░ 68%   │
├───────────────────────────────────────────────────────────────────────┤
│ ✓ git            0.4s   ✓ curl  0.2s   ⤼ jq (cached)   ✗ opi  exit 4  │
└─ [p]ause [l]ogs [r]etry [s]kip [d]etail [q]uit ───────────────────────┘
```

- **Live streamed output** from the running command (enabled by §8.1) — the single biggest
  perceived-quality change.
- Concurrent lanes when `maxParallel > 1`.
- Keyboard actions: pause after current item, open logs, retry failed item, skip item, quit after
  phase.
- A restart/logout checkpoint screen showing the **exact resume command**, copyable.
- Read-only TUI mode for `plan`, `status`, and `doctor`.
- **Theming** consistent with the maintainer's Catppuccin setup, and a `mono` TTY-safe mode
  (`obsctl-rs` does exactly this — 29 themes plus `mono`; match that bar).

### 10.3 Plain-mode output

- Structured, greppable, colour-aware-but-degradable.
- A final summary table: installed / skipped / failed / drifted, with durations.
- **HTML/Markdown run report** export (`fluxion report --format html`) for onboarding records.

---

## 11. Workstream F — Codebase Refactoring

Ordered so that each step is independently shippable and test-covered.

### 11.1 Dissolve the orchestrator god object (§3.5)

1. Delete the eight dead fields; make the constructor honest.
2. Extract `StepExecutor` SPI: `plan(Step, Ctx) → List<Effect>`, `probe(Step, Ctx) → Status`,
   `execute(Step, Ctx) → Outcome`, `explain(Step) → Explanation`.
3. Migrate all 21 module types off the `switch` onto the SPI, one per commit, each with tests.
4. **Delete `dryRunModule` entirely** — dry-run becomes "render the effects, don't interpret them"
   (§5.3.5). This removes ~110 lines of duplicated dispatch.
5. What remains of `BootstrapOrchestratorImpl` is a scheduler: ~150 lines coordinating phases,
   dependencies, state, retries, and events. Target: **952 → under 200 lines.**
6. Registry becomes the single dispatch point; `throw new IllegalStateException("… executor
   missing")` arms disappear.

### 11.2 Unify the two config frontends (§3.6)

1. Define the IR (`ResolvedPlan`, `ResolvedStep`, `StepSpec` sealed hierarchy) in `core`.
2. Both frontends become `Document → IR` mappers. Everything downstream (validation of semantics,
   interpolation, `when`, DAG, fingerprint, plan rendering) moves behind the IR and is written once.
3. Replace the duplicated string sets in `WorkstationProfileValidator` with a single
   `PlanKind` sealed enum/registry carrying: id, aliases, category, supported actions, required
   fields, and doc link. Adding a kind touches one file.
4. Target: `WorkstationProfileValidator` 790 → ~150 lines; `WorkstationProfileConfigMapper`
   679 → ~200.

### 11.3 Purity and testability

- Introduce `Result<T,E>` and `Validation<E,T>` (§5.3).
- Inject `Clock` everywhere `Instant.now()` is called.
- Make `SkipEvaluator` pure; the engine threads state.
- Replace `ExecutionPausedException` control flow with an `Outcome.Paused` variant.
- Collapse `ApplicationContext.forCli` / `forTui` into one builder parameterised by a
  `RuntimeMode` — they differ only in the runner and the sudo provider.
- Enforce with **ArchUnit** tests: module direction, no `null` returns from public methods,
  `core` has no infrastructure imports, all `switch`es over sealed types are exhaustive.

### 11.4 Naming and structure

- The package is `dev.sysboot`, the directory is `sysboot/`, the product is `fluxion`. Plan a
  **single mechanical rename** (`dev.sysboot` → `dev.fluxion`, `sysboot/` → `app/` or repo root) as
  one isolated commit with no behavioural change, once §11.1–11.2 land. Leaving it costs a small
  tax on every new contributor and every doc page.
- Adopt an `docs/adr/` folder; record the decisions in this plan (no Vavr, JLine over Lanterna,
  delegate-don't-duplicate, Jackson vs SnakeYAML Engine) as ADRs.

---

## 12. Workstream G — Testing

Current: 60 tests, all fast, all unit-level. Good, but it let a 404 URL ship (§3.1).

| Layer | Add |
| --- | --- |
| **Property** (jqwik) | DAG planner (topological order is always valid; cycles always detected), interpolator (idempotence, no unresolved `${}` in output), fingerprint (stable under key reordering, changes under value change), merge semantics for `include` |
| **Golden/snapshot** | `plan`, `explain`, `graph`, `diff`, `status`, `report` output for a fixture corpus — locks the UX contract |
| **Contract** | For each `worxbend` tool: a recorded fixture of its `--json`/`plan` output, plus a **network-gated live test** that resolves the real release asset URL and checksum. This is the specific regression net for §3.1. |
| **Integration** (Testcontainers) | Real `fedora:latest`, `archlinux:latest`, `opensuse/tumbleweed`, `debian:stable` containers running `fluxion apply` on small profiles. Gated to a nightly CI lane, not every PR. |
| **Process/resilience** | Deliberately hanging children, children that emit >1 MiB to stdout, children killed mid-run, SIGINT during apply, disk-full during state write |
| **Mutation** (PIT) | On `core` and `config-parser` — where the pure logic lives |
| **Native-image** | Run the golden CLI suite **against the native binary**, not only the JVM build. Several native-only failure modes (reflection, resources, JLine) are invisible today. |
| **Fuzz** | YAML parser against malformed/adversarial input |

Also: a `just test-fast` / `just test-all` split so the inner loop stays under 10 s.

---

## 13. Workstream H — Build, CI, Release, Supply Chain

- **Mill 1.1.6 → 1.1.7** now; evaluate 1.2.x post-RC.
- **JDK matrix** in CI: 25 / 26 / 27-EA (§5.1).
- **Error Prone + NullAway** in the compile gate; keep `-Xlint:all -Werror` as the target.
- **Native image**: multi-arch (`linux-amd64` **and** `linux-arm64`), profile-guided optimisation
  evaluation, binary-size budget assertion, and startup-time regression check.
- **Release**: `.deb` / `.rpm` / `.pkg.tar.zst` packages, an AUR PKGBUILD, a Homebrew tap, and a
  Snap — the maintainer's own tools already ship via Snap and install scripts; Fluxion should be at
  least as easy to install as the things it installs.
- **`install.sh`** matching the `binstaller` pattern: checksum + sigstore verification, pinnable
  version, no unsolicited shell-rc modification.
- **Supply chain**: CycloneDX SBOM per artifact, **sigstore/cosign keyless signing** of every
  release asset (parity with `binstaller`), SLSA provenance attestation, and a documented
  verification procedure in the README.
- **Version metadata from the build**, not a hard-coded constant in `VersionProvider.java` that the
  release workflow greps with a regex.
- Generated command reference published as a release artifact from picocli help.
- Docs and example configs already ship in the tarball — keep that, add completions and the JSON
  schema.

---

## 14. Workstream I — Documentation

- **Quickstart that starts from `fluxion init`**, not from a 500-line sample config.
- **A migration guide**: "from `Justfile` + scripts to Fluxion", walking through
  `fluxion migrate` on the real `system-bootstrap` layout, including what it cannot classify.
- **A tool-integration page** per `worxbend` tool: what Fluxion delegates, what it adds, how to pin
  versions, how verification works.
- **Cookbook**: Fedora workstation, Arch + AUR, openSUSE + Sway, dotfiles-only, CLI-only server.
- **Troubleshooting**: package-manager failures, sudo, Flatpak, native-image, PTY.
- **Glossary** (job, phase, step, item, probe, state, fingerprint, checkpoint) and a
  **compatibility matrix** (distros × package managers × shells × tested GraalVM).
- **Terminal captures** (asciinema/VHS) of `plan`, `apply` with live output, and a restart resume.
- ADR folder (§11.4).
- Reconcile `MEMORY.md`, which currently lists TamboUI and pty4j as dependencies that do not exist
  in the build.

---

## 15. Milestones

Sequenced so that adoption value arrives early and refactoring is de-risked by tests.

### M0 — Correctness and truth (1 week)

The bugs that make the tool unsafe or embarrassing.

- Fix the `nerd-fonts` URL (§3.1) + add the live-asset contract test.
- Rewrite `DefaultShellRunner` / `PtyShellRunner` for correct streaming and timeouts (§3.2).
- Fix the sudo password `String` leak (§3.3).
- Delete the eight dead orchestrator fields (§3.5 step 1).
- Correct `MEMORY.md` / `AGENTS.md` where they describe dependencies that do not exist.
- **Exit criteria:** a hanging child is killed at its timeout; a 100 MB-output child does not
  deadlock; the `nerd-fonts` step installs a real font on a real machine.

### M1 — Streaming, sudo-once, cancellation (1–2 weeks)

- `ProcessRunner` port + event-stream output pumping (§8.1).
- Single sudo authorisation with keep-alive (§8.2).
- Ctrl-C → graceful stop, state flush, resume hint.
- Per-step `timeout`, `retries`, `expectExitCodes`, `probe` on `commands` (§7.7).
- **Exit criteria:** `fluxion apply` on a profile containing `zypper dup` shows live output, prompts
  for sudo exactly once, and survives Ctrl-C without orphaning processes.

### M2 — ToolBroker + `binstaller` / `dotbot` / `nerd-fonts` integration (2–3 weeks)

- `ToolBroker` with verified, cached, arch-aware tool installation (§6.1).
- `binstaller-profile` plan kind with `plan`/`apply`/`versions`/`lock` mapping (§6.2).
- `dotfiles-apply` on real `dotbot plan --output json` (§6.3).
- `nerd-fonts` reading the user's own config, per-family progress (§6.4).
- `fluxion tools` command.
- **Exit criteria:** the maintainer's existing `~/.config/binstaller/config.yaml`,
  `install.conf.yaml`, and `nerd-fonts-installer/config.yaml` are driven unchanged from one Fluxion
  profile, and `fluxion dry-run` previews all three accurately.

### M3 — Capability parity (2–3 weeks)

- `git-config`, `git-repo`, `systemd-unit`, `system-setting`, `user-groups`, `gpg-key`,
  `system-update`, `cargo-binstall-packages`, `snap-packages` (§7.1–7.5).
- Unified `repository` kind including the zypper operations (§7.6).
- **Exit criteria:** every line of `system-bootstrap`'s shared scripts and the openSUSE path is
  expressible in typed steps or explicitly documented as an intentional `commands` escape hatch.

### M4 — `fluxion migrate` and the adoption on-ramp (1–2 weeks)

- `fluxion migrate --from <repo>` with a coverage report (§7.8).
- Improved `import --from-host`.
- `fluxion init`.
- **Exit criteria:** `fluxion migrate --from ~/.system-bootstrap` produces a profile that passes
  `validate` and whose `plan` a human can review against the original scripts. A checked-in fixture
  makes this a CI test.

### M5 — Refactor: SPI, IR, purity (3–4 weeks)

- `StepExecutor` SPI, all kinds migrated, `dryRunModule` deleted (§11.1).
- Unified IR, single validator, `PlanKind` registry (§11.2).
- `Result`/`Validation`, `Clock` injection, pure `SkipEvaluator`, ArchUnit gates (§11.3).
- Property + mutation tests on the now-pure core (§12).
- **Exit criteria:** `BootstrapOrchestratorImpl` under 200 lines; adding a step kind touches one
  registry file plus one executor plus one test; ArchUnit gates green.

### M6 — Diagnostics, schema, CLI polish (2 weeks)

- Structured diagnostics with line/column and codes (§9.2).
- Generated JSON Schema + editor support (§9.3).
- `include`, `tags`, `enabled`, extended vars (§9.1).
- Completions, `--format json` everywhere, `resume`, `retry --failed`, `why`, `kinds` (§9.4).
- **Exit criteria:** a typo in a plan kind produces a pointed, single-screen error with a
  suggestion; VS Code autocompletes a Fluxion profile.

### M7 — TUI rebuild (2–3 weeks)

- JLine foundation, native-image verified (§10.1).
- New layout with live output, lanes, keyboard actions, checkpoint screen, themes (§10.2).
- Read-only TUI for `plan`/`status`/`doctor`.
- **Exit criteria:** an `apply` of the full workstation profile is watchable end-to-end without
  flicker, with live command output, and is pausable.

### M8 — Parallelism and state depth (2 weeks)

- Bounded parallel DAG execution with resource locks (§8.3).
- Failure classification, retry/backoff, mirror fallback (§8.4).
- Versioned state with migrations, run ids, history, journal, locking, backup/restore (§8.5).
- **Exit criteria:** full-profile wall-clock cut by ≥40 % versus sequential, with no package-manager
  lock contention; a killed run resumes exactly where it stopped.

### M9 — Platform, supply chain, release (1–2 weeks)

- JDK 26 primary + 27-EA lane; Mill 1.1.7; dependency refresh (§5.1, §5.4).
- Multi-arch native builds, SBOM, sigstore signing, SLSA provenance,
  `.deb`/`.rpm`/AUR/Snap/Homebrew, `install.sh` (§13).
- Testcontainers nightly distro matrix; native-binary golden suite (§12).
- **Exit criteria:** `curl … | sh` installs a signature-verified Fluxion on amd64 and arm64;
  nightly CI proves apply works on four real distros.

### M10 — Docs, ADRs, and the rename (1–2 weeks)

- Full documentation set (§14).
- ADR folder backfilled.
- `dev.sysboot` → `dev.fluxion` mechanical rename in one isolated commit (§11.4).

*Indicative sequencing, not a commitment. M0–M2 are the ones that change whether this tool gets
used; if only three milestones ship, ship those.*

---

## 16. Definition of "Best in Class"

Measurable acceptance criteria for the whole plan.

| Dimension | Bar |
| --- | --- |
| Adoption | The maintainer's own machine is bootstrapped by `fluxion apply`, and `system-bootstrap` keeps only the dotfiles + one Fluxion profile |
| Correctness | Every timeout is enforced; no unbounded buffers; no orphaned children; state survives kill -9 |
| Coverage | 100 % of `system-bootstrap`'s operations are expressible; ≥85 % as typed steps |
| Delegation | Zero duplicated functionality with `binstaller` / `dotbot-go` / `nerd-fonts-installer` |
| Feedback | Live output for every long-running command; ≤100 ms input latency in the TUI |
| Privilege | Exactly one sudo prompt per run |
| Speed | Native binary starts in <30 ms; full profile ≥40 % faster than sequential |
| Safety | Dry-run is accurate for every step kind; destructive steps are flagged before execution |
| Diagnostics | Every config error has a code, a file:line:col, and a suggestion |
| Trust | Every downloaded artifact is checksum-verified; signature-verified where upstream publishes one; Fluxion's own releases are sigstore-signed with SBOM + provenance |
| Testing | Property tests on the pure core, contract tests per external tool, real-distro integration tests, golden tests against the native binary |
| Code health | No file over 300 lines; no method over 20; ArchUnit-enforced boundaries; one dispatch mechanism |
| Docs | A new user goes from zero to a bootstrapped machine using only the quickstart |

---

## 17. Non-Goals

- Not a package manager, not a configuration-management daemon, not Ansible.
- No Spring / CDI / Quarkus / runtime DI / service locators / classpath scanning.
- No Gradle, Maven, or SBT.
- No rewrite in another language. Java 25→27 stays.
- No replacement of `binstaller` / `dotbot-go` / `nerd-fonts-installer` — the opposite.
- No Windows support. macOS only where the delegated tools already support it.
- No uninstall/rollback of system packages in this plan (planning-only `diff` is in scope; actual
  rollback is a separate, much larger design).
- No daemon or watch mode.

---

## 18. Risks and Open Decisions

| Risk | Mitigation |
| --- | --- |
| JLine does not work cleanly in the native image | Timeboxed spike **before** M7 starts; fallback is the current renderer plus JLine only for input |
| GraalVM stable for JDK 26/27 lags | Keep the native build on the GraalVM 25.x line; JDK 26/27 applies to the JVM build and CI matrix first |
| PTY support adds a JNI dependency the native image dislikes | `Pty` port with a non-PTY fallback; PTY is opt-in per step, never required |
| External tool CLIs change | Contract tests with recorded fixtures + a network-gated live test per tool; pin tool versions by default |
| Parallelism causes package-manager lock contention | Resource locks per package manager; `maxParallel: 1` restores sequential behaviour |
| The refactor (M5) destabilises a working tool | Property + golden tests land **before** the refactor; one kind migrated per commit |
| Scope is large for one maintainer | M0–M2 are independently valuable and ship first |

**Open decisions to make before M2:**

1. Jackson 2.x + a position index, or SnakeYAML Engine for parsing? (Driven by §9.2.)
2. JLine 3.30.x or JLine 4.x? (Driven by the native-image spike.)
3. Does `binstaller` need a stable `--format json` on `plan`/`versions`, or is text parsing
   acceptable? — **Recommendation: add `--json` to `binstaller` upstream.** It is the maintainer's
   own tool; a stable machine-readable envelope (which `obsctl-rs` already has) makes the Fluxion
   integration robust instead of fragile.
4. Same question for `nerd-fonts-installer --dry-run` output.
5. Rename `dev.sysboot` → `dev.fluxion` at M10, or earlier?
