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
  public boolean supports(ModuleItem item) {
    return item.itemType() == ItemType.PACKAGE
        && item.packageManager().map(pm -> pm == PackageManagerKind.ZYPPER).orElse(false);
  }

  @Override
  public InstallationStatus probe(String packageName) {
    var result = shellRunner.run(List.of("rpm", "-q", packageName), Map.of(), PROBE_TIMEOUT);

    if (result.exitCode() == 0) {
      return new InstallationStatus.InstalledByProbe(
          packageName, extractVersion(result.stdout().strip(), packageName));
    }
    if (result.exitCode() == 1) {
      return new InstallationStatus.NotInstalled(packageName);
    }
    return new InstallationStatus.Unknown(
        packageName, "rpm -q exited %d: %s".formatted(result.exitCode(), result.stderr()));
  }

  private String extractVersion(String rpmLine, String packageName) {
    // "rpm -q docker-compose" prints "docker-compose-2.29.7-1.fc41.x86_64". A package name can
    // itself contain hyphens, so the name is removed as a literal prefix rather than splitting on
    // the first hyphen, which misreads a hyphenated name's own dash as the version separator.
    String prefix = packageName + "-";
    if (rpmLine.startsWith(prefix)) {
      return rpmLine.substring(prefix.length());
    }
    int dash = rpmLine.indexOf('-');
    return dash >= 0 ? rpmLine.substring(dash + 1) : null;
  }
}
