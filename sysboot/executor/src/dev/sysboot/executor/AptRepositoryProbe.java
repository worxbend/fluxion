package dev.sysboot.executor;

import dev.sysboot.core.AptRepositorySourceSetup;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.InstalledProbe;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.ShellRunner;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class AptRepositoryProbe implements InstalledProbe {

  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);

  private final ShellRunner shellRunner;

  public AptRepositoryProbe(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  @Override
  public boolean supports(ItemType itemType) {
    return itemType == ItemType.APT_REPOSITORY;
  }

  @Override
  public InstallationStatus probe(String sourceListPath) {
    var result = shellRunner.run(List.of("test", "-s", sourceListPath), Map.of(), PROBE_TIMEOUT);
    return switch (result.exitCode()) {
      case 0 -> new InstallationStatus.InstalledByProbe(sourceListPath, null);
      case 1 -> new InstallationStatus.NotInstalled(sourceListPath);
      default ->
          new InstallationStatus.Unknown(
              sourceListPath,
              "APT repository probe failed (exit %d): %s"
                  .formatted(result.exitCode(), result.stderr()));
    };
  }

  @Override
  public InstallationStatus probe(ModuleItem item) {
    if (item.sourceSetup().orElse(null) instanceof AptRepositorySourceSetup setup) {
      return configuredProbe(item, setup);
    }
    return probe(item.key());
  }

  private InstallationStatus configuredProbe(ModuleItem item, AptRepositorySourceSetup setup) {
    if (!item.key().equals(setup.sourceListPath().toString())) {
      return new InstallationStatus.NotInstalled(item.key());
    }
    boolean sourceMatches =
        RepositoryConfigVerifier.lines(shellRunner, setup.sourceListPath(), PROBE_TIMEOUT)
            .map(
                lines -> lines.stream().map(String::strip).filter(line -> !line.isEmpty()).toList())
            .filter(lines -> lines.equals(List.of(setup.sourceEntry().strip())))
            .isPresent();
    // Installed APT keyrings are dearmored, so the downloaded artifact digest cannot attest them.
    // Re-run verified publication until an installed-key fingerprint is persisted.
    boolean trustMatches = false;
    return sourceMatches && trustMatches
        ? new InstallationStatus.InstalledByProbe(item.key(), null)
        : new InstallationStatus.NotInstalled(item.key());
  }
}
