package dev.sysboot.executor;

import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.InstalledProbe;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ShellRunner;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class PacmanRepositoryProbe implements InstalledProbe {

  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);

  private final ShellRunner shellRunner;

  public PacmanRepositoryProbe(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  @Override
  public boolean supports(ItemType itemType) {
    return itemType == ItemType.PACMAN_REPOSITORY;
  }

  @Override
  public InstallationStatus probe(String repositoryName) {
    var result =
        shellRunner.run(
            List.of("grep", "-Eq", "^\\[" + repositoryName + "\\]$", "/etc/pacman.conf"),
            Map.of(),
            PROBE_TIMEOUT);
    return switch (result.exitCode()) {
      case 0 -> new InstallationStatus.InstalledByProbe(repositoryName, null);
      case 1 -> new InstallationStatus.NotInstalled(repositoryName);
      default ->
          new InstallationStatus.Unknown(
              repositoryName,
              "Pacman repository probe failed (exit %d): %s"
                  .formatted(result.exitCode(), result.stderr()));
    };
  }
}
