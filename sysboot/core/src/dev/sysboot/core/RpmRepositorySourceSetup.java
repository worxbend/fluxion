package dev.sysboot.core;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record RpmRepositorySourceSetup(
    ModuleName name,
    String repositoryId,
    URI baseUrl,
    Path repoFilePath,
    Optional<URI> gpgKeyUrl,
    boolean enabled,
    boolean gpgCheck)
    implements SourceSetup {

  public RpmRepositorySourceSetup {
    Objects.requireNonNull(name, "Source name must not be null");
    Objects.requireNonNull(repositoryId, "RPM repository id must not be null");
    Objects.requireNonNull(baseUrl, "RPM repository base URL must not be null");
    Objects.requireNonNull(repoFilePath, "RPM repository file path must not be null");
    gpgKeyUrl = gpgKeyUrl == null ? Optional.empty() : gpgKeyUrl;
    if (repositoryId.isBlank()) {
      throw new IllegalArgumentException("RPM repository id must not be blank");
    }
  }

  @Override
  public PackageManagerKind packageManager() {
    return PackageManagerKind.DNF;
  }
}
