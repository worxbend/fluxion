package dev.sysboot.core;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record ZypperRepositorySourceSetup(
    ModuleName name,
    String repositoryId,
    URI baseUrl,
    Path repoFilePath,
    Optional<URI> gpgKeyUrl,
    boolean enabled,
    boolean gpgCheck)
    implements SourceSetup {

  public ZypperRepositorySourceSetup {
    Objects.requireNonNull(name, "Source name must not be null");
    Objects.requireNonNull(repositoryId, "Zypper repository id must not be null");
    Objects.requireNonNull(baseUrl, "Zypper repository base URL must not be null");
    Objects.requireNonNull(repoFilePath, "Zypper repository file path must not be null");
    gpgKeyUrl = gpgKeyUrl == null ? Optional.empty() : gpgKeyUrl;
    if (repositoryId.isBlank()) {
      throw new IllegalArgumentException("Zypper repository id must not be blank");
    }
  }

  @Override
  public PackageManagerKind packageManager() {
    return PackageManagerKind.ZYPPER;
  }
}
