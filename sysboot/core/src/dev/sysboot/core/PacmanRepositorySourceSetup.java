package dev.sysboot.core;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record PacmanRepositorySourceSetup(
    ModuleName name,
    String repositoryName,
    URI server,
    Path configPath,
    Optional<String> sigLevel,
    Optional<Path> include,
    boolean enabled)
    implements SourceSetup {

  public PacmanRepositorySourceSetup {
    Objects.requireNonNull(name, "Source name must not be null");
    Objects.requireNonNull(repositoryName, "Pacman repository name must not be null");
    Objects.requireNonNull(server, "Pacman repository server must not be null");
    Objects.requireNonNull(configPath, "Pacman config path must not be null");
    sigLevel = sigLevel == null ? Optional.empty() : sigLevel;
    include = include == null ? Optional.empty() : include;
    if (repositoryName.isBlank()) {
      throw new IllegalArgumentException("Pacman repository name must not be blank");
    }
  }

  @Override
  public PackageManagerKind packageManager() {
    return PackageManagerKind.PACMAN;
  }
}
