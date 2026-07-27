package dev.sysboot.executor;

import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.InterruptModule;
import dev.sysboot.core.InterruptResumeMode;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.PhaseStateEntry;
import dev.sysboot.core.PhaseStatus;
import dev.sysboot.core.PlanEntryStateEntry;
import dev.sysboot.core.PlanEntryStatus;
import dev.sysboot.core.StateEntry;
import dev.sysboot.core.StateRepository;
import dev.sysboot.core.StepResult;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Everything the orchestrator writes to, or reads back from, persisted run state.
 *
 * <p>Extracted because it was roughly a quarter of a thousand-line class and had nothing to do with
 * orchestration: every method here is "load, modify, save, refresh the skip evaluator". Keeping
 * that pattern in one place also means the skip evaluator can no longer be left un-refreshed after
 * a write, which is the sort of thing that only shows up as a step being run twice.
 *
 * <p>A {@link Clock} is injected so timestamps are testable — {@code Instant.now()} scattered
 * through the orchestrator was one of the things PLAN-i.md §3.8 flagged as untestable.
 */
final class RunStateRecorder {

  private static final String STATE_SCHEMA_VERSION = "1.0.0";

  private final Optional<StateRepository> repository;
  private final String profileName;
  private final SkipEvaluator skipEvaluator;
  private final PhaseFingerprintCalculator fingerprintCalculator;
  private final Clock clock;

  RunStateRecorder(
      Optional<StateRepository> repository,
      String profileName,
      SkipEvaluator skipEvaluator,
      PhaseFingerprintCalculator fingerprintCalculator) {
    this(repository, profileName, skipEvaluator, fingerprintCalculator, Clock.systemUTC());
  }

  RunStateRecorder(
      Optional<StateRepository> repository,
      String profileName,
      SkipEvaluator skipEvaluator,
      PhaseFingerprintCalculator fingerprintCalculator,
      Clock clock) {
    this.repository = repository;
    this.profileName = profileName;
    this.skipEvaluator = skipEvaluator;
    this.fingerprintCalculator = fingerprintCalculator;
    this.clock = clock;
  }

  /** Stamps the run's manifest identity, refusing state left behind by a different profile. */
  void prepare(BootstrapConfig config) {
    repository.ifPresent(
        repo -> {
          String identity = config.profileName().value();
          String fingerprint = fingerprintCalculator.manifestFingerprint(config);
          BootstrapState current = load(repo);
          rejectStale(current, identity, fingerprint);
          save(repo, current.withManifestMetadata(identity, fingerprint));
        });
  }

  boolean isPhaseAlreadyCompleted(Phase phase, String fingerprint) {
    return repository
        .flatMap(repo -> repo.load(profileName))
        .map(state -> state.isPhaseCompleted(phase.name().value(), fingerprint))
        .orElse(false);
  }

  /**
   * Where to resume within a phase.
   *
   * <p>Consuming the marker here is deliberate: a resumed phase must not keep skipping to the same
   * entry on a later run.
   */
  int resumeStartIndex(List<BootstrapModule> modules) {
    Optional<String> nextEntry =
        repository.flatMap(repo -> repo.load(profileName)).flatMap(BootstrapState::nextPlanEntry);
    if (nextEntry.isEmpty()) {
      return 0;
    }
    for (int index = 0; index < modules.size(); index++) {
      if (modules.get(index).name().value().equals(nextEntry.orElseThrow())) {
        clearNextPlanEntry();
        return index;
      }
    }
    return 0;
  }

  void recordSuccess(ModuleName moduleName, String itemKey, ItemType itemType, StepResult result) {
    recordSuccess(moduleName, itemKey, itemType, result, Optional.empty());
  }

  /** Records the source URL alongside the item, so {@code status} can report version drift. */
  void recordBinarySuccess(CompiledBinaryModule module, String itemKey, StepResult result) {
    recordSuccess(
        module.name(),
        itemKey,
        ItemType.COMPILED_BINARY,
        result,
        Optional.of(module.url().toString()));
  }

  void recordPhase(
      PhaseName phase, PhaseStatus status, String fingerprint, Optional<String> reason) {
    repository.ifPresent(
        repo ->
            save(
                repo,
                load(repo)
                    .withPhaseEntry(
                        new PhaseStateEntry(
                            phase.value(), status, now(), Optional.of(fingerprint), reason))));
  }

  void recordInterrupt(InterruptModule module, Optional<String> nextEntry) {
    repository.ifPresent(
        repo ->
            save(
                repo,
                load(repo)
                    .withPlanEntry(
                        new PlanEntryStateEntry(
                            module.name().value(),
                            interruptStatus(module),
                            now(),
                            Optional.of(module.message())))
                    .withNextPlanEntry(nextEntry)));
  }

  /** Marks where a cancelled run should pick up. */
  void recordResumePoint(Optional<String> nextEntry) {
    repository.ifPresent(repo -> save(repo, load(repo).withNextPlanEntry(nextEntry)));
  }

  /** The entry a resume should start from after an interrupt. */
  static Optional<String> nextPlanEntry(
      InterruptModule module, Optional<ModuleName> followingModule) {
    if (module.resumeFrom() == InterruptResumeMode.CURRENT) {
      return Optional.of(module.name().value());
    }
    return followingModule.map(ModuleName::value);
  }

  static PlanEntryStatus interruptStatus(InterruptModule module) {
    return module.resumeFrom() == InterruptResumeMode.CURRENT
        ? PlanEntryStatus.INTERRUPTED
        : PlanEntryStatus.COMPLETED;
  }

  private void recordSuccess(
      ModuleName moduleName,
      String itemKey,
      ItemType itemType,
      StepResult result,
      Optional<String> sourceUrl) {
    if (!(result instanceof StepResult.Success success)) {
      return;
    }
    repository.ifPresent(
        repo ->
            skipEvaluator.refreshState(
                repo.recordSuccess(
                    profileName,
                    new StateEntry(
                        profileName,
                        moduleName.value(),
                        itemKey,
                        itemType,
                        now(),
                        success.detectedVersion(),
                        success.checksum(),
                        sourceUrl))));
  }

  private void clearNextPlanEntry() {
    repository.ifPresent(
        repo ->
            repo.load(profileName)
                .map(BootstrapState::withoutNextPlanEntry)
                .ifPresent(updated -> save(repo, updated)));
  }

  private void rejectStale(BootstrapState state, String identity, String fingerprint) {
    if (!state.hasRecordedWork()) {
      return;
    }
    if (state.manifestIdentity().filter(identity::equals).isEmpty()) {
      throw stale("manifest identity");
    }
    if (state.manifestFingerprint().filter(fingerprint::equals).isEmpty()) {
      throw stale("manifest fingerprint");
    }
  }

  private StaleStateException stale(String reason) {
    return new StaleStateException(
        "Saved state is stale: "
            + reason
            + " differs. Reset state with `fluxion state reset "
            + profileName
            + " --force` or re-run apply with --reset-state.");
  }

  private BootstrapState load(StateRepository repo) {
    return repo.load(profileName)
        .orElseGet(() -> BootstrapState.empty(profileName, STATE_SCHEMA_VERSION));
  }

  /** Every write refreshes the skip evaluator, so a later step cannot decide from stale state. */
  private void save(StateRepository repo, BootstrapState state) {
    repo.save(state);
    skipEvaluator.refreshState(state);
  }

  private Instant now() {
    return clock.instant();
  }
}
