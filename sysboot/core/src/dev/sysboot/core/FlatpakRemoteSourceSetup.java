package dev.sysboot.core;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

public record FlatpakRemoteSourceSetup(
    ModuleName name, String remote, URI url, boolean system, Optional<Sha256Digest> artifactSha256)
    implements SourceSetup {

  public FlatpakRemoteSourceSetup {
    Objects.requireNonNull(name, "Source name must not be null");
    Objects.requireNonNull(remote, "Flatpak remote name must not be null");
    Objects.requireNonNull(url, "Flatpak remote URL must not be null");
    artifactSha256 = artifactSha256 == null ? Optional.empty() : artifactSha256;
    RepositoryIdentifierPolicy.requireSafe(remote, "Flatpak remote name");
    SourceUrlPolicy.requireHttps(url, "Flatpak repository descriptor URL");
    if (artifactSha256.isEmpty()) {
      throw new IllegalArgumentException(
          "Flatpak repository descriptor requires a SHA-256 checksum");
    }
  }

  @Override
  public PackageManagerKind packageManager() {
    return PackageManagerKind.FLATPAK;
  }
}
