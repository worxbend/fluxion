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

public final class DnfPackageProbe implements InstalledProbe {

  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

  private final ShellRunner shellRunner;

  public DnfPackageProbe(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  @Override
  public boolean supports(ItemType itemType) {
    return itemType == ItemType.PACKAGE;
  }

  @Override
  public boolean supports(ModuleItem item) {
    return item.itemType() == ItemType.PACKAGE
        && item.packageManager().map(pm -> pm == PackageManagerKind.DNF).orElse(true);
  }

  @Override
  public InstallationStatus probe(String packageName) {
    var result = shellRunner.run(List.of("rpm", "-q", packageName), Map.of(), PROBE_TIMEOUT);

    if (result.exitCode() == 0) {
      return new InstallationStatus.InstalledByProbe(
          packageName, extractVersion(result.stdout().strip()));
    }
    if (result.exitCode() == 1) {
      return new InstallationStatus.NotInstalled(packageName);
    }
    return new InstallationStatus.Unknown(
        packageName, "rpm -q exited %d: %s".formatted(result.exitCode(), result.stderr()));
  }

  private String extractVersion(String rpmLine) {
    int firstDash = rpmLine.indexOf('-');
    return firstDash >= 0 ? rpmLine.substring(firstDash + 1) : null;
  }
}
