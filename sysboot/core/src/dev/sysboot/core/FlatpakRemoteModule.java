package dev.sysboot.core;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

public record FlatpakRemoteModule(
    ModuleName name, String remote, URI url, boolean system, Optional<Sha256Digest> artifactSha256)
    implements BootstrapModule {

  public FlatpakRemoteModule {
    Objects.requireNonNull(name, "Module name must not be null");
    Objects.requireNonNull(remote, "Remote must not be null");
    Objects.requireNonNull(url, "Remote URL must not be null");
    artifactSha256 = artifactSha256 == null ? Optional.empty() : artifactSha256;
    RepositoryIdentifierPolicy.requireSafe(remote, "Flatpak remote name");
    SourceUrlPolicy.requireHttps(url, "Flatpak repository descriptor URL");
    if (artifactSha256.isEmpty()) {
      throw new IllegalArgumentException(
          "Flatpak repository descriptor requires a SHA-256 checksum");
    }
  }

  public FlatpakRemoteSourceSetup asSourceSetup() {
    return new FlatpakRemoteSourceSetup(name, remote, url, system, artifactSha256);
  }
}
