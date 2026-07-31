package dev.sysboot.executor;

import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.InstalledProbe;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.RpmRepositorySourceSetup;
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

  @Override
  public InstallationStatus probe(ModuleItem item) {
    if (item.sourceSetup().orElse(null) instanceof RpmRepositorySourceSetup setup) {
      return configuredProbe(item, setup);
    }
    return probe(item.key());
  }

  private InstallationStatus configuredProbe(ModuleItem item, RpmRepositorySourceSetup setup) {
    if (!item.key().equals(setup.repoFilePath().toString())) {
      return new InstallationStatus.NotInstalled(item.key());
    }
    var values =
        RepositoryConfigVerifier.lines(shellRunner, setup.repoFilePath(), PROBE_TIMEOUT)
            .map(lines -> RepositoryConfigVerifier.iniSection(lines, setup.repositoryId()))
            .orElse(Map.of());
    boolean configMatches =
        setup.repositoryId().equals(values.get("name"))
            && setup.baseUrl().toString().equals(values.get("baseurl"))
            && (setup.enabled() ? "1" : "0").equals(values.get("enabled"))
            && (setup.gpgCheck() ? "1" : "0").equals(values.get("gpgcheck"))
            && values.size() == (setup.gpgKeyUrl().isPresent() ? 5 : 4);
    boolean trustMatches = setup.gpgKeyUrl().isEmpty() && !values.containsKey("gpgkey");
    return configMatches && trustMatches
        ? new InstallationStatus.InstalledByProbe(item.key(), null)
        : new InstallationStatus.NotInstalled(item.key());
  }
}
