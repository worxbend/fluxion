package dev.sysboot.core;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record AptRepositoryModule(
    ModuleName name,
    String sourceEntry,
    Path sourceListPath,
    Optional<URI> signingKeyUrl,
    Optional<Path> keyringPath,
    Optional<Sha256Digest> artifactSha256)
    implements BootstrapModule {

  public AptRepositoryModule {
    Objects.requireNonNull(name, "Module name must not be null");
    Objects.requireNonNull(sourceEntry, "APT source entry must not be null");
    Objects.requireNonNull(sourceListPath, "APT source list path must not be null");
    signingKeyUrl = signingKeyUrl == null ? Optional.empty() : signingKeyUrl;
    keyringPath = keyringPath == null ? Optional.empty() : keyringPath;
    artifactSha256 = artifactSha256 == null ? Optional.empty() : artifactSha256;
    if (sourceEntry.isBlank()) {
      throw new IllegalArgumentException("APT source entry must not be blank");
    }
    sourceListPath = RepositoryDestinationPolicy.requireAptSourceList(sourceListPath);
    keyringPath = keyringPath.map(RepositoryDestinationPolicy::requireAptKeyring);
    SourceUrlPolicy.aptRepositoryUri(sourceEntry);
    SourceUrlPolicy.requireAuthenticatedAptSource(
        sourceEntry,
        keyringPath.orElseThrow(
            () -> new IllegalArgumentException("APT source requires a configured keyring path")));
    signingKeyUrl.ifPresent(url -> SourceUrlPolicy.requireHttps(url, "APT signing-key URL"));
    requireArtifactTrust(signingKeyUrl, keyringPath, artifactSha256);
  }

  public AptRepositoryModule(
      ModuleName name,
      String sourceEntry,
      Path sourceListPath,
      Optional<URI> signingKeyUrl,
      Optional<Path> keyringPath) {
    this(name, sourceEntry, sourceListPath, signingKeyUrl, keyringPath, Optional.empty());
  }

  public AptRepositorySourceSetup asSourceSetup() {
    return new AptRepositorySourceSetup(
        name, sourceEntry, sourceListPath, signingKeyUrl, keyringPath, artifactSha256);
  }

  private static void requireArtifactTrust(
      Optional<URI> url, Optional<Path> keyring, Optional<Sha256Digest> checksum) {
    if (url.isPresent() != checksum.isPresent()) {
      throw new IllegalArgumentException(
          "APT signing-key URL and SHA-256 checksum must be configured together");
    }
    if (url.isPresent() && keyring.isEmpty()) {
      throw new IllegalArgumentException("APT signing-key URL requires a keyring path");
    }
  }
}
