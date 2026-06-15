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

public final class PacmanPackageProbe implements InstalledProbe {

  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

  private final ShellRunner shellRunner;

  public PacmanPackageProbe(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  @Override
  public boolean supports(ItemType itemType) {
    return itemType == ItemType.PACKAGE;
  }

  @Override
  public boolean supports(ModuleItem item) {
    return item.itemType() == ItemType.PACKAGE
        && item.packageManager().map(PacmanPackageProbe::isPacmanBacked).orElse(false);
  }

  @Override
  public InstallationStatus probe(String packageName) {
    var result = shellRunner.run(List.of("pacman", "-Q", packageName), Map.of(), PROBE_TIMEOUT);

    if (result.exitCode() == 0) {
      return new InstallationStatus.InstalledByProbe(
          packageName, extractVersion(result.stdout().strip()));
    }
    if (result.exitCode() == 1) {
      return new InstallationStatus.NotInstalled(packageName);
    }
    return new InstallationStatus.Unknown(
        packageName, "pacman -Q exited %d: %s".formatted(result.exitCode(), result.stderr()));
  }

  private String extractVersion(String pacmanLine) {
    // pacman -Q output: "git 2.45.2-1"
    int space = pacmanLine.indexOf(' ');
    return space >= 0 ? pacmanLine.substring(space + 1) : null;
  }

  private static boolean isPacmanBacked(PackageManagerKind kind) {
    return kind == PackageManagerKind.PACMAN
        || kind == PackageManagerKind.PARU
        || kind == PackageManagerKind.YAY;
  }
}
