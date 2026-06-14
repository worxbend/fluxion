package dev.sysboot.executor;

import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.SkipDecision;
import dev.sysboot.core.StateEntry;
import java.util.Optional;

/**
 * Central skip-decision logic.
 *
 * <p>Decision precedence when skipAlreadyInstalled=true: 1. State file hit (unless --re-probe) →
 * Skip(InstalledFromState). 2. Live probe InstalledByProbe → Skip(InstalledByProbe). 3. Live probe
 * NotInstalled or Unknown → Run (fail-safe for Unknown).
 */
public final class SkipEvaluator {

  private final Optional<BootstrapState> state;
  private final InstalledProbeRegistry probeRegistry;
  private final boolean skipAlreadyInstalled;
  private final boolean reProbe;

  public SkipEvaluator(
      Optional<BootstrapState> state,
      InstalledProbeRegistry probeRegistry,
      boolean skipAlreadyInstalled,
      boolean reProbe) {
    this.state = state;
    this.probeRegistry = probeRegistry;
    this.skipAlreadyInstalled = skipAlreadyInstalled;
    this.reProbe = reProbe;
  }

  public SkipDecision evaluate(String itemKey, ItemType itemType) {
    if (!skipAlreadyInstalled) {
      return new SkipDecision.Run(itemKey);
    }

    if (!reProbe) {
      Optional<StateEntry> stateEntry = state.flatMap(s -> s.findEntry(itemKey, itemType));
      if (stateEntry.isPresent()) {
        StateEntry entry = stateEntry.get();
        return new SkipDecision.Skip(
            itemKey,
            new InstallationStatus.InstalledFromState(
                itemKey, entry.completedAt(), entry.version()));
      }
    }

    InstallationStatus probeResult = probeRegistry.probe(itemKey, itemType);
    return switch (probeResult) {
      case InstallationStatus.InstalledByProbe p -> new SkipDecision.Skip(itemKey, p);
      case InstallationStatus.InstalledFromState s -> new SkipDecision.Skip(itemKey, s);
      case InstallationStatus.NotInstalled ignored -> new SkipDecision.Run(itemKey);
      case InstallationStatus.Unknown ignored -> new SkipDecision.Run(itemKey);
    };
  }

  public static SkipEvaluator alwaysRun() {
    return new SkipEvaluator(
        Optional.empty(), new InstalledProbeRegistry(java.util.List.of()), false, false);
  }
}
