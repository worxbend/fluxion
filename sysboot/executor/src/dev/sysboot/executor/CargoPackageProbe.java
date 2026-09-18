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
import java.util.Optional;

/**
 * {@code cargo install --list} ignores the package name it is asked about and always exits 0, so
 * unlike {@code rpm -q} or {@code pacman -Q} its exit code says nothing about whether a given crate
 * is installed. The listing itself has to be scanned instead, the way {@link FlatpakProbe} scans
 * {@code flatpak list}.
 */
public final class CargoPackageProbe implements InstalledProbe {

  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);

  private final ShellRunner shellRunner;

  public CargoPackageProbe(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  @Override
  public boolean supports(ItemType itemType) {
    return itemType == ItemType.PACKAGE;
  }

  @Override
  public boolean supports(ModuleItem item) {
    return item.itemType() == ItemType.PACKAGE
        && item.packageManager().map(pm -> pm == PackageManagerKind.CARGO).orElse(false);
  }

  @Override
  public InstallationStatus probe(String packageName) {
    var result = shellRunner.run(List.of("cargo", "install", "--list"), Map.of(), PROBE_TIMEOUT);

    if (result.exitCode() != 0) {
      return new InstallationStatus.Unknown(
          packageName,
          "cargo install --list exited %d: %s".formatted(result.exitCode(), result.stderr()));
    }

    return matchingLine(result.stdout(), packageName)
        .map(line -> new InstallationStatus.InstalledByProbe(packageName, extractVersion(line)))
        .map(InstallationStatus.class::cast)
        .orElseGet(() -> new InstallationStatus.NotInstalled(packageName));
  }

  private Optional<String> matchingLine(String stdout, String packageName) {
    String prefix = packageName + " v";
    return stdout.lines().filter(line -> line.startsWith(prefix)).findFirst();
  }

  private String extractVersion(String line) {
    // "ripgrep v14.1.0:" and "mycrate v0.1.0 (/home/me/src):" both name the crate at column 0
    // followed by " v", so the version is whatever separates that from the next space or colon.
    String afterName = line.substring(line.indexOf(" v") + 2);
    int end = afterName.indexOf(' ');
    String version = end >= 0 ? afterName.substring(0, end) : afterName;
    return version.endsWith(":") ? version.substring(0, version.length() - 1) : version;
  }
}
