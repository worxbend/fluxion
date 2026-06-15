package dev.sysboot.executor;

import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleItem;
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

  private Optional<BootstrapState> state;
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
    return evaluate(new ModuleItem(new dev.sysboot.core.ModuleName("unknown"), itemKey, itemType));
  }

  public SkipDecision evaluate(ModuleItem item) {
    if (!skipAlreadyInstalled) {
      return new SkipDecision.Run(item.key());
    }

    if (!reProbe) {
      Optional<StateEntry> stateEntry = state.flatMap(s -> s.findEntry(item.key(), item.itemType()));
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

  public static SkipEvaluator alwaysRun() {
    return new SkipEvaluator(
        Optional.empty(), new InstalledProbeRegistry(java.util.List.of()), false, false);
  }
}
