package dev.sysboot.executor;

import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.InstalledProbe;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ShellRunner;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** openSUSE uses the RPM database; probe strategy is identical to DNF. */
public final class ZypperPackageProbe implements InstalledProbe {

  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

  private final ShellRunner shellRunner;

  public ZypperPackageProbe(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  @Override
  public boolean supports(ItemType itemType) {
    return itemType == ItemType.PACKAGE;
  }

  @Override
  public InstallationStatus probe(String packageName) {
    var result = shellRunner.run(List.of("rpm", "-q", packageName), Map.of(), PROBE_TIMEOUT);

    if (result.exitCode() == 0) {
      String line = result.stdout().strip();
      int dash = line.indexOf('-');
      String version = dash >= 0 ? line.substring(dash + 1) : null;
      return new InstallationStatus.InstalledByProbe(packageName, version);
    }
    if (result.exitCode() == 1) {
      return new InstallationStatus.NotInstalled(packageName);
    }
    return new InstallationStatus.Unknown(
        packageName, "rpm -q exited %d: %s".formatted(result.exitCode(), result.stderr()));
  }
}
