package dev.sysboot.executor;

import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.InstalledProbe;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.ShellRunner;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class DotbotProbe implements InstalledProbe {

  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

  private final ShellRunner shellRunner;
  private final Map<String, String> probeCommandsByKey;

  public DotbotProbe(ShellRunner shellRunner, Map<String, String> probeCommandsByKey) {
    this.shellRunner = shellRunner;
    this.probeCommandsByKey = Map.copyOf(probeCommandsByKey);
  }

  @Override
  public boolean supports(ItemType itemType) {
    return itemType == ItemType.DOTBOT;
  }

  @Override
  public InstallationStatus probe(String itemKey) {
    return probe(itemKey, probeCommandsByKey.get(itemKey));
  }

  @Override
  public InstallationStatus probe(ModuleItem item) {
    String configured =
        item.configuredModule()
            .filter(DotbotModule.class::isInstance)
            .map(DotbotModule.class::cast)
            .flatMap(DotbotModule::probeCommand)
            .orElse(null);
    return probe(item.key(), configured != null ? configured : probeCommandsByKey.get(item.key()));
  }

  private InstallationStatus probe(String itemKey, String probeCommand) {
    if (probeCommand == null) {
      return new InstallationStatus.Unknown(
          itemKey, "No probeCommand configured for dotbot module");
    }
    var result = shellRunner.run(List.of("/bin/sh", "-c", probeCommand), Map.of(), PROBE_TIMEOUT);
    return result.exitCode() == 0
        ? new InstallationStatus.InstalledByProbe(itemKey, null)
        : new InstallationStatus.NotInstalled(itemKey);
  }
}
