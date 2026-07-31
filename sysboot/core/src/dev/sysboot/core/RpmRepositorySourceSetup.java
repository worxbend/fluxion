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
    boolean gpgCheck,
    Optional<Sha256Digest> artifactSha256)
    implements SourceSetup {

  public RpmRepositorySourceSetup {
    Objects.requireNonNull(name, "Source name must not be null");
    Objects.requireNonNull(repositoryId, "RPM repository id must not be null");
    Objects.requireNonNull(baseUrl, "RPM repository base URL must not be null");
    Objects.requireNonNull(repoFilePath, "RPM repository file path must not be null");
    gpgKeyUrl = gpgKeyUrl == null ? Optional.empty() : gpgKeyUrl;
    artifactSha256 = artifactSha256 == null ? Optional.empty() : artifactSha256;
    repoFilePath = RepositoryDestinationPolicy.requireRpmRepository(repoFilePath);
    RepositoryIdentifierPolicy.requireSafe(repositoryId, "RPM repository id");
    SourceUrlPolicy.requireHttps(baseUrl, "RPM repository base URL");
    gpgKeyUrl.ifPresent(url -> SourceUrlPolicy.requireHttps(url, "RPM signing-key URL"));
    requireArtifactTrust(gpgKeyUrl, enabled, gpgCheck, artifactSha256);
  }

  public RpmRepositorySourceSetup(
      ModuleName name,
      String repositoryId,
      URI baseUrl,
      Path repoFilePath,
      Optional<URI> gpgKeyUrl,
      boolean enabled,
      boolean gpgCheck) {
    this(name, repositoryId, baseUrl, repoFilePath, gpgKeyUrl, enabled, gpgCheck, Optional.empty());
  }

  private static void requireArtifactTrust(
      Optional<URI> url, boolean enabled, boolean gpgCheck, Optional<Sha256Digest> checksum) {
    if (url.isPresent() != checksum.isPresent()) {
      throw new IllegalArgumentException(
          "RPM signing-key URL and SHA-256 checksum must be configured together");
    }
    if (gpgCheck && url.isEmpty()) {
      throw new IllegalArgumentException("RPM gpgCheck requires a signing-key URL");
    }
    if (enabled && !gpgCheck) {
      throw new IllegalArgumentException("Enabled RPM repositories must enforce gpgCheck");
    }
  }

  @Override
  public PackageManagerKind packageManager() {
    return PackageManagerKind.DNF;
  }
}
