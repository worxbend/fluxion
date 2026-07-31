package dev.sysboot.executor;

import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.SkipDecision;
import dev.sysboot.core.StateEntry;
import java.util.Optional;

/**
 * Central skip-decision logic.
 *
 * <p>Skip-recorded mode consults state before probing. Live re-probe mode bypasses state and always
 * probes. Record-only mode executes without making a skip decision.
 */
public final class SkipEvaluator {

  private Optional<BootstrapState> state;
  private final InstalledProbeRegistry probeRegistry;
  private final RunStateMode runStateMode;

  public SkipEvaluator(
      Optional<BootstrapState> state,
      InstalledProbeRegistry probeRegistry,
      boolean skipAlreadyInstalled,
      boolean reProbe) {
    this(state, probeRegistry, RunStateMode.fromOptions(skipAlreadyInstalled, reProbe));
  }

  public SkipEvaluator(
      Optional<BootstrapState> state,
      InstalledProbeRegistry probeRegistry,
      RunStateMode runStateMode) {
    this.state = state;
    this.probeRegistry = probeRegistry;
    this.runStateMode = runStateMode;
  }

  public SkipDecision evaluate(ModuleItem item) {
    if (!runStateMode.probesInstalledItems()) {
      return new SkipDecision.Run(item.key());
    }

    if (runStateMode.skipsRecordedWork()
        && item.sourceSetup().isEmpty()
        && item.configuredModule().stream().noneMatch(CompiledBinaryModule.class::isInstance)) {
      Optional<StateEntry> stateEntry =
          state.flatMap(saved -> saved.findEntry(item.moduleName(), item.key(), item.itemType()));
      if (stateEntry.isPresent()) {
        StateEntry entry = stateEntry.get();
        return new SkipDecision.Skip(
            item.key(),
            new InstallationStatus.InstalledFromState(
                item.key(), entry.completedAt(), entry.version().orElse(null)));
      }
    }

    InstallationStatus probeResult = probeRegistry.probe(item);
    return switch (probeResult) {
      case InstallationStatus.InstalledByProbe p -> new SkipDecision.Skip(item.key(), p);
      case InstallationStatus.InstalledFromState s -> new SkipDecision.Skip(item.key(), s);
      case InstallationStatus.NotInstalled ignored -> new SkipDecision.Run(item.key());
      case InstallationStatus.Unknown ignored -> new SkipDecision.Run(item.key());
    };
  }

  public void refreshState(BootstrapState updatedState) {
    this.state = Optional.of(updatedState);
  }

  RunStateMode runStateMode() {
    return runStateMode;
  }

  public static SkipEvaluator alwaysRun() {
    return new SkipEvaluator(
        Optional.empty(),
        new InstalledProbeRegistry(java.util.List.of()),
        RunStateMode.RECORD_ONLY);
  }
}
