package dev.sysboot.core;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record AptRepositorySourceSetup(
    ModuleName name,
    String sourceEntry,
    Path sourceListPath,
    Optional<URI> signingKeyUrl,
    Optional<Path> keyringPath)
    implements SourceSetup {

  public AptRepositorySourceSetup {
    Objects.requireNonNull(name, "Source name must not be null");
    Objects.requireNonNull(sourceEntry, "APT source entry must not be null");
    Objects.requireNonNull(sourceListPath, "APT source list path must not be null");
    signingKeyUrl = signingKeyUrl == null ? Optional.empty() : signingKeyUrl;
    keyringPath = keyringPath == null ? Optional.empty() : keyringPath;
    if (sourceEntry.isBlank()) {
      throw new IllegalArgumentException("APT source entry must not be blank");
    }
  }

  @Override
  public PackageManagerKind packageManager() {
    return PackageManagerKind.APT;
  }
}
