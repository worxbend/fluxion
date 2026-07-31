# Prompt-Driven Hardening Audit

This audit applies every review prompt in `prompts/` to the active `sysboot/` codebase. The prompt
checkout was compared with `JeremyMorgan/Claude-Code-Reviewing-Prompts` at commit
`90916aceb110bc0c64ea9b9db298f63f232832f5`: all 22 Code Quality and Security prompts, plus the
upstream README, were present byte-for-byte.

The prompts are review lenses rather than independent acceptance standards. Findings were
deduplicated, reproduced against this repository, repaired at the owning layer, and then challenged
with focused adversarial tests. Web authentication, cookies, databases, and public HTTP APIs are
not part of Fluxion; those prompts were still evaluated for analogous local trust boundaries.

## Code Quality Prompts

| Prompt | Evaluation and applied outcome |
|---|---|
| `code-duplication-detection` | Repeated orchestration switches and execution/reporting paths were the principal duplication. The orchestrator was decomposed into source, phase, dispatch, and item components; package and direct item behavior now converge on shared execution/state helpers. Repository trust paths are converging on one verified source executor. |
| `code-quality-metrics-standards` | Oversized and high-coupling classes were identified as the dominant maintainability risk. `BootstrapOrchestratorImpl` was reduced substantially, state locking was extracted from JSON persistence, and pinned official Google Checkstyle, project Checkstyle, PMD, SpotBugs, and google-java-format gates now run locally, in CI, and before a release build. Parser validation and fingerprint calculation remain the main candidates for further extraction. |
| `design-pattern-implementation` | Constructor injection and explicit registries remain the composition model. `ModuleExecutor`, `ModuleDispatcher`, `StepBinding`, `ItemExecution`, and `RunStateMode` replace mirrored switches and implicit boolean behavior without adding runtime DI or service lookup. Configured Dotbot and shell-script probe commands now survive canonical item binding instead of being dropped by production composition. |
| `error-handling-resilience` | Download, state, shell, TUI, and CLI boundaries now translate specific failures, preserve the original failure during cleanup, and fail closed before privileged mutation. A failed source setup gates its package phase even when sibling work may continue, and trust failures are no longer converted into persisted success by `continueOnError`. |
| `exception-flow-analysis` | Shell failures, state write failures, paused execution, and TUI runtime failures were traced to stable CLI exit classifications. Cancellation and phase failure now persist an accurate resume boundary rather than marking work complete. |
| `initial-software-design-analyis` | The module direction `cli -> app -> tui -> executor -> config-parser -> core` was preserved. The audit found the original orchestrator and mapper concentration, state identity drift, and duplicated source execution to be the highest architectural risks. |
| `readability-and-naming` | Boolean state behavior is being replaced by named modes; module-qualified item identities are explicit; URL display/state identities are separated from request URLs; trust objects use exact digest and fingerprint value types. |
| `resilience-fault-tolerance` | Network transfers are bounded and deadline-controlled, partial files are removed, state replacement is atomic and locked, and cancellation is boundary-aware. Compiled-binary replacement now uses same-directory hard-link backups for regular files, preserves the live path until the final atomic move, rejects unsafe destination types and physical install/link aliases, and retries or reconciles pre-effect and post-effect failures. Delegated installs place private backups outside managed outputs, retain them across partial restore failure, support idempotent retry, restore regular files, symlinks, prior absences, and removed parent hierarchy, and refuse newly introduced symlink ancestors before touching another tree. |
| `solid-principles` | Composition, planning, dispatch, direct multi-item execution, and state recording were split into focused collaborators. New behavior extends existing ports and registries rather than introducing parallel frameworks. |
| `testing-implementation` | Focused tests cover traversal, physical path aliases, ancestor symlinks, concurrent state updates, command injection, redaction across chunks, cancellation, checksum/signature mismatch, metadata-only archive output, immutable privileged staging, ambiguous-effect binary rollback, retryable delegated restoration, compound failure diagnostics, partial downloads, read-only preview commands, recursive CLI help, release policy, and native-image metadata. The settled tree passes every Mill test module, static analysis, assembly, native-image, shipped-config, and trusted-byte gate. |

## Security Prompts

| Prompt | Evaluation and applied outcome |
|---|---|
| `api-and-infrastructure` | Fluxion has no network service API. The analogous supply-chain surface was CI/release automation: workflow permissions were minimized, third-party actions were pinned by commit, release tags are checked against the built commit, and publish credentials are confined to the publish job. |
| `authentication-flow-review` | There is no user authentication flow. Sudo password acquisition is the relevant credential flow: password bytes are zeroed, output is redacted, one authenticated session is shared by privileged executor paths, and its timestamp is invalidated when the session closes. |
| `authorization-implementation` | Local privilege escalation is explicit through `sudo`. Remote privileged scripts and compiled artifacts are copied into root-owned immutable stages and inspected or consumed only from those stages. The digest computed while streaming the HTTP response is required by the first root-stage consumer; detached-signature verification, supplemental checksum resolution, archive extraction, and final publication therefore bind to the same bytes, including signature-only installs. Root-stage cleanup must also succeed before the destination transaction starts. Binary metadata is prepared before publication, and structured privileged calls replace embedded shell privilege boundaries. |
| `business-logic-vulnerabilities` | Dry-run defaults, reset-state behavior, phase filtering, cancellation, resume state, item identity, `--re-probe`, confirmation policy, and `continueOnError` were treated as security-relevant state-machine behavior rather than UI details. Plan and dry-run now share apply's semantic validation while using a read-only state context, and remotely keyed repository probes conservatively force a verified republish when no durable attestation exists. |
| `comprehensive-security-report` | The combined pass produced a deduplicated set of reproduced findings spanning supply chain, remote execution, state, filesystem, process, privilege, logging, and configuration trust. Later critics closed incomplete local-input and secret fingerprints, dirty pinned Git worktrees, source-failure gating, caller-bound artifact digests, privileged metadata ordering, archive TOCTOU, binary replacement crash windows, physical path aliases, retryable rollback, external-tree restoration hazards, stale delegated output, and masked compound failures. Fixes are tracked by boundary and verified independently rather than counted per prompt. |
| `database-security` | Not applicable: Fluxion has no database or query layer. Its JSON state store was reviewed as the persistence analogue and hardened with safe profile paths, no-follow checks, private permissions, atomic writes, and per-profile process/file locking. |
| `file-handling-business-logic` | State, file-write, keyring, script, source-artifact, archive, and binary-install paths were challenged for traversal, ancestor and leaf symlink following, physical aliases, special files, races, disclosure, ambiguous archive members, partial replacement, permissions, and cleanup. Privileged archive extraction reads only a no-follow regular immutable stage; a stage over the documented 1 GiB bound is rejected before hashing or consumer execution; cleanup failures are surfaced; and regular-binary replacement keeps the prior inode reachable through a hard-link backup until commit. |
| `initial-security-analysis` | The initial threat model prioritized root mutations, remote code/artifacts, state-driven skips, secret-bearing output, and release provenance. These priorities drove the implementation waves and adversarial re-reviews. |
| `input-validation` | Package names reject option/control injection; shell login arguments are quoted; remote URLs require HTTPS without credentials; checksums and signer fingerprints are exact typed values; repository names, paths, archive members, and YAML item identities are validated before execution. |
| `logging-monitoring` | CLI and TUI output share bounded redaction for credentials, auth headers, tokens, URLs, JSON, environment values, and PEM material, including split-chunk secrets. TUI event queues and rendered text are bounded and sanitized. |
| `secrets-management-audit` | Sudo input is never logged, sensitive environment values contribute only domain-separated digests to state fingerprints, signed URL query/fragment data is excluded from display/state identities, and release workflows avoid interpolating untrusted input into shell scripts. External Dotbot, Binstaller, and Nerd Font configuration bytes also contribute to phase fingerprints so local-input changes cannot inherit stale completion state. |
| `session-cookie-security` | Not applicable: there are no HTTP sessions or cookies. The analogous cached sudo session was reviewed for lifetime, prompt routing, keepalive shutdown, password zeroing, and accidental reuse beyond the execution context. |

## Verification Policy

No finding is considered closed solely because code was edited. Each repair receives focused tests,
an independent adversarial review where the trust impact is high, repository-wide format and test
gates, configuration validation, CI policy checks, assembly, and a GraalVM native-image build.
Residual limitations and environment-dependent checks are reported explicitly rather than hidden
behind a successful unit-test count.

The review corpus was recovered with one isolated worker per prompt: the first pass completed 20
of 22 workers, and an extended retry completed the two timeouts, yielding 22 of 22 prompt reviews.
Cross-prompt findings were deduplicated and judged adversarially. Three post-remediation critics
completed, one release-auditor worker timed out, and three blind judges independently challenged
the resulting fixes; earlier snapshots were not treated as release evidence. Two cache-distinct
targeted pairs subsequently re-opened response-to-stage identity, privileged cleanup ordering,
binary and symlink ambiguous-effect rollback, destination topology, delegated backup survival,
removed-parent restoration, large stages, and compound failure reporting. Every retained,
reachable finding from those passes received a deterministic regression before the settled-tree
gates below. A cache-distinct closure pair then found eight unique reachable edge cases after
deduplication: physical aliases, binary rollback retry/reconciliation, delegated backup retention,
delegate-created ancestor symlinks, runtime-to-result translation, primary-failure preservation,
privileged compound diagnostics, and metadata-only archive output. Those cases were repaired and
covered by focused regressions before the final verification wave. That verification pair retained
two last reachable branches: tentative binary recovery before a transaction change object exists,
and loss of the cleanup command's actual `ProcessResult` detail. Early and late recovery now share
the same bounded identity-aware reconciliation, and sole, compound, and exception cleanup failures
retain their real diagnostics; parameterized pre-effect and post-effect regressions cover regular,
symlink, and initially absent destinations.

## Settled-Tree Verification

The post-remediation tree was checked with:

- `./mill __.test`: every test module passed.
- `just quality-check`: official Google Checkstyle plus project Checkstyle 13.9.0, PMD 7.26.0,
  and SpotBugs 4.10.3 passed; SpotBugs had zero unsuppressed high- or medium-priority findings.
- `just format-check`, `just native-metadata-check`, `just ci-policy-check`, `git diff --check`,
  YAML workflow parsing, and validation of every shipped profile: passed.
- `./mill cli.assembly` plus JVM help, version, generation, and shipped-profile validation smokes:
  passed.
- `./mill cli.nativeImage` plus native help, version, generation, logging, and shipped-profile
  validation smokes: passed on GraalVM CE 25.0.2.
- Online verification of every catalogued known-tool release and literal asset digest: passed.

The CI and release native smokes generate a Debian profile because their pinned Ubuntu runners
provide `apt-get`; generating a Fedora profile there made `doctor` fail before it could serve as a
release gate.

## Residual Boundaries

- Profile-authored shell remains intentionally trusted arbitrary code, and package-manager
  lifecycle hooks and repositories remain part of the host trust base.
- Full end-to-end execution still needs representative Fedora, Arch, openSUSE, and Debian hosts;
  local validation and CI cannot reproduce every package-manager and privilege environment.
- Exact downloaded archives and exact extracted executable stages are independently hashed; those
  digests are intentionally different.
- Remote-key repository attestation is not persisted yet, so affected sources are safely
  republished on subsequent apply runs at the cost of extra package-manager work.
- Process death between multi-file transaction steps has no durable transaction journal. The
  hard-link strategy prevents an absent regular-binary destination but abrupt termination may
  leave recoverable backup debris. Post-commit backup deletion is also deliberately best effort:
  a cleanup failure can leave debris, but does not remove the installed destination.
- Explicitly selected external installers execute as the invoking user and remain part of that
  same-UID trust boundary. The delegated guard recovers ordinary nonzero, rejected-output, and
  cleanup failure paths; it is not a sandbox against a deliberately malicious external tool.
- Known GitHub release tags are mutable upstream, but literal per-asset digests bind the accepted
  bytes. The project does not yet implement a full Sigstore verification path.
- The native release is built on Ubuntu 22.04 and therefore documents a glibc 2.35 compatibility
  floor. Nerd Font configuration remains an external boundary, and `dotbot-scala` remains
  Linux-only.
