package dev.sysboot.executor;

import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.InstalledProbe;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.ShellRunner;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class AptPackageProbe implements InstalledProbe {

  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);
  private static final String INSTALLED_STATUS = "install ok installed";

  private final ShellRunner shellRunner;

  public AptPackageProbe(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  @Override
  public boolean supports(ItemType itemType) {
    return itemType == ItemType.PACKAGE;
  }

  @Override
  public boolean supports(ModuleItem item) {
    return item.itemType() == ItemType.PACKAGE
        && item.packageManager().map(pm -> pm == PackageManagerKind.APT).orElse(false);
  }

  @Override
  public InstallationStatus probe(String packageName) {
    var statusResult =
        shellRunner.run(
            List.of("dpkg-query", "-W", "-f=${Status}\\t${Version}", packageName),
            Map.of(),
            PROBE_TIMEOUT);

    if (statusResult.exitCode() != 0) {
      return new InstallationStatus.NotInstalled(packageName);
    }

    String output = statusResult.stdout().strip();
    String[] parts = output.split("\t", 2);
    String status = parts[0];
    String version = parts.length > 1 ? parts[1] : null;

    if (INSTALLED_STATUS.equals(status)) {
      return new InstallationStatus.InstalledByProbe(packageName, version);
    }
    return new InstallationStatus.NotInstalled(packageName);
  }
}
