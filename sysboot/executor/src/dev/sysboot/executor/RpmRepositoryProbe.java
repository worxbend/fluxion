package dev.sysboot.executor;

import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.InstalledProbe;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ShellRunner;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class RpmRepositoryProbe implements InstalledProbe {

  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);

  private final ShellRunner shellRunner;

  public RpmRepositoryProbe(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  @Override
  public boolean supports(ItemType itemType) {
    return itemType == ItemType.RPM_REPOSITORY;
  }

  @Override
  public InstallationStatus probe(String repoFilePath) {
    var result = shellRunner.run(List.of("test", "-s", repoFilePath), Map.of(), PROBE_TIMEOUT);
    return switch (result.exitCode()) {
      case 0 -> new InstallationStatus.InstalledByProbe(repoFilePath, null);
      case 1 -> new InstallationStatus.NotInstalled(repoFilePath);
      default ->
          new InstallationStatus.Unknown(
              repoFilePath,
              "RPM repository probe failed (exit %d): %s"
                  .formatted(result.exitCode(), result.stderr()));
    };
  }
}
