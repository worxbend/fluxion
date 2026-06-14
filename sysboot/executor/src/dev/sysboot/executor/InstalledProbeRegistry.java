package dev.sysboot.executor;

import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.InstalledProbe;
import dev.sysboot.core.ItemType;
import java.util.List;
import java.util.Objects;

public final class InstalledProbeRegistry {

  private final List<InstalledProbe> probes;

  public InstalledProbeRegistry(List<InstalledProbe> probes) {
    Objects.requireNonNull(probes);
    this.probes = List.copyOf(probes);
  }

  public InstallationStatus probe(String itemKey, ItemType itemType) {
    return probes.stream()
        .filter(p -> p.supports(itemType))
        .findFirst()
        .map(p -> p.probe(itemKey))
        .orElse(
            new InstallationStatus.Unknown(
                itemKey, "No probe registered for item type: " + itemType));
  }
}
