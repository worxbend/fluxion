package dev.sysboot.executor;

import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.InstalledProbe;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.ModuleName;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class InstalledProbeRegistry {

  private final List<InstalledProbe> probes;

  public InstalledProbeRegistry(List<InstalledProbe> probes) {
    Objects.requireNonNull(probes);
    this.probes = List.copyOf(probes);
  }

  public InstallationStatus probe(String itemKey, ItemType itemType) {
    return probe(new ModuleItem(new ModuleName("unknown"), itemKey, itemType));
  }

  public InstallationStatus probe(ModuleItem item) {
    Optional<InstalledProbe> probe = probes.stream().filter(p -> p.supports(item)).findFirst();
    if (probe.isEmpty()) {
      return new InstallationStatus.Unknown(
          item.key(), "No probe registered for " + describe(item));
    }
    try {
      return probe.orElseThrow().probe(item);
    } catch (ShellExecutionException failure) {
      return new InstallationStatus.Unknown(item.key(), "Probe command could not be executed");
    }
  }

  private String describe(ModuleItem item) {
    Optional<?> packageManager = item.packageManager();
    return packageManager
        .map(pm -> item.itemType() + " using " + pm)
        .orElse(item.itemType().toString());
  }
}
