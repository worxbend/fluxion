package dev.sysboot.executor;

import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.InstalledProbe;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ShellRunner;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class FlatpakProbe implements InstalledProbe {

  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);

  private final ShellRunner shellRunner;

  public FlatpakProbe(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  @Override
  public boolean supports(ItemType itemType) {
    return itemType == ItemType.FLATPAK;
  }

  @Override
  public InstallationStatus probe(String appId) {
    var result =
        shellRunner.run(
            List.of("flatpak", "list", "--app", "--columns=application"), Map.of(), PROBE_TIMEOUT);

    if (result.exitCode() != 0) {
      return new InstallationStatus.Unknown(
          appId, "flatpak list failed (exit %d): %s".formatted(result.exitCode(), result.stderr()));
    }

    boolean found = result.stdout().lines().map(String::strip).anyMatch(appId::equals);

    return found
        ? new InstallationStatus.InstalledByProbe(appId, null)
        : new InstallationStatus.NotInstalled(appId);
  }
}
