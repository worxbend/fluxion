package dev.sysboot.core;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record PacmanRepositoryModule(
    ModuleName name,
    String repositoryName,
    URI server,
    Path configPath,
    Optional<String> sigLevel,
    Optional<Path> include,
    boolean enabled)
    implements BootstrapModule {

  public PacmanRepositoryModule {
    Objects.requireNonNull(name, "Module name must not be null");
    Objects.requireNonNull(repositoryName, "Pacman repository name must not be null");
    Objects.requireNonNull(server, "Pacman repository server must not be null");
    Objects.requireNonNull(configPath, "Pacman config path must not be null");
    sigLevel = sigLevel == null ? Optional.empty() : sigLevel.map(String::strip);
    include = include == null ? Optional.empty() : include;
    RepositoryIdentifierPolicy.requireSafe(repositoryName, "Pacman repository name");
    SourceUrlPolicy.requireHttps(server, "Pacman repository server URL");
    PacmanRepositoryPolicy.validate(configPath, sigLevel, include, enabled);
    configPath = RepositoryDestinationPolicy.requirePacmanConfig(configPath);
    include = include.map(RepositoryDestinationPolicy::requirePacmanInclude);
  }

  public PacmanRepositorySourceSetup asSourceSetup() {
    return new PacmanRepositorySourceSetup(
        name, repositoryName, server, configPath, sigLevel, include, enabled);
  }
}
