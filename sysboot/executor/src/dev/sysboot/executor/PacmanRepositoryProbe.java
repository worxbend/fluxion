package dev.sysboot.executor;

import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.InstalledProbe;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.PacmanRepositorySourceSetup;
import dev.sysboot.core.ShellRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PacmanRepositoryProbe implements InstalledProbe {

  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);
  private static final Path DEFAULT_CONFIG = Path.of("/etc/pacman.conf");

  private final ShellRunner shellRunner;
  private final Path configPath;

  public PacmanRepositoryProbe(ShellRunner shellRunner) {
    this(shellRunner, DEFAULT_CONFIG);
  }

  PacmanRepositoryProbe(ShellRunner shellRunner, Path configPath) {
    this.shellRunner = Objects.requireNonNull(shellRunner);
    this.configPath = Objects.requireNonNull(configPath);
  }

  @Override
  public boolean supports(ItemType itemType) {
    return itemType == ItemType.PACMAN_REPOSITORY;
  }

  @Override
  public InstallationStatus probe(String repositoryName) {
    var result =
        shellRunner.run(
            List.of("grep", "-Fqx", "--", "[" + repositoryName + "]", configPath.toString()),
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

  @Override
  public InstallationStatus probe(ModuleItem item) {
    if (item.sourceSetup().orElse(null) instanceof PacmanRepositorySourceSetup setup) {
      return configuredProbe(item, setup);
    }
    return probe(item.key());
  }

  private InstallationStatus configuredProbe(ModuleItem item, PacmanRepositorySourceSetup setup) {
    if (!item.key().equals(setup.repositoryName())) {
      return new InstallationStatus.NotInstalled(item.key());
    }
    List<String> lines =
        RepositoryConfigVerifier.lines(shellRunner, setup.configPath(), PROBE_TIMEOUT)
            .map(all -> RepositoryConfigVerifier.sectionLines(all, setup.repositoryName()))
            .orElse(List.of());
    var expected = new java.util.ArrayList<String>();
    expected.add(setting(setup.enabled(), "Server = " + setup.server()));
    setup
        .sigLevel()
        .ifPresent(value -> expected.add(setting(setup.enabled(), "SigLevel = " + value)));
    setup.include().ifPresent(path -> expected.add(setting(setup.enabled(), "Include = " + path)));
    boolean matches = lines.equals(expected);
    return matches
        ? new InstallationStatus.InstalledByProbe(item.key(), null)
        : new InstallationStatus.NotInstalled(item.key());
  }

  private String setting(boolean enabled, String value) {
    return enabled ? value : "# " + value;
  }
}
