package dev.sysboot.executor;

import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.InstalledProbe;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.ShellScriptModule;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class ShellScriptProbe implements InstalledProbe {

  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
  private static final String NO_PROBE_REASON =
      "No probeCommand configured for this script. "
          + "Add a 'probeCommand' field to the shell-script module in your config.";

  private final ShellRunner shellRunner;
  private final Map<String, String> probeCommandsByScriptPath;

  public ShellScriptProbe(ShellRunner shellRunner, Map<String, String> probeCommandsByScriptPath) {
    this.shellRunner = shellRunner;
    this.probeCommandsByScriptPath = Map.copyOf(probeCommandsByScriptPath);
  }

  @Override
  public boolean supports(ItemType itemType) {
    return itemType == ItemType.SHELL_SCRIPT;
  }

  @Override
  public InstallationStatus probe(String scriptPath) {
    return probe(scriptPath, probeCommandsByScriptPath.get(scriptPath));
  }

  @Override
  public InstallationStatus probe(ModuleItem item) {
    String configured =
        item.configuredModule()
            .filter(ShellScriptModule.class::isInstance)
            .map(ShellScriptModule.class::cast)
            .flatMap(ShellScriptModule::probeCommand)
            .orElse(null);
    return probe(
        item.key(), configured != null ? configured : probeCommandsByScriptPath.get(item.key()));
  }

  private InstallationStatus probe(String scriptPath, String probeCommand) {
    if (probeCommand == null) {
      return new InstallationStatus.Unknown(scriptPath, NO_PROBE_REASON);
    }

    var result = shellRunner.run(List.of("/bin/sh", "-c", probeCommand), Map.of(), PROBE_TIMEOUT);

    return result.exitCode() == 0
        ? new InstallationStatus.InstalledByProbe(scriptPath, null)
        : new InstallationStatus.NotInstalled(scriptPath);
  }
}
