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
    boolean gpgCheck,
    boolean autoRefresh,
    Optional<Sha256Digest> artifactSha256)
    implements SourceSetup {

  public ZypperRepositorySourceSetup {
    Objects.requireNonNull(name, "Source name must not be null");
    Objects.requireNonNull(repositoryId, "Zypper repository id must not be null");
    Objects.requireNonNull(baseUrl, "Zypper repository base URL must not be null");
    Objects.requireNonNull(repoFilePath, "Zypper repository file path must not be null");
    gpgKeyUrl = gpgKeyUrl == null ? Optional.empty() : gpgKeyUrl;
    artifactSha256 = artifactSha256 == null ? Optional.empty() : artifactSha256;
    repoFilePath = RepositoryDestinationPolicy.requireZypperRepository(repoFilePath);
    RepositoryIdentifierPolicy.requireSafe(repositoryId, "Zypper repository id");
    SourceUrlPolicy.requireHttps(baseUrl, "Zypper repository base URL");
    gpgKeyUrl.ifPresent(url -> SourceUrlPolicy.requireHttps(url, "Zypper signing-key URL"));
    requireArtifactTrust(gpgKeyUrl, enabled, gpgCheck, artifactSha256);
  }

  public ZypperRepositorySourceSetup(
      ModuleName name,
      String repositoryId,
      URI baseUrl,
      Path repoFilePath,
      Optional<URI> gpgKeyUrl,
      boolean enabled,
      boolean gpgCheck) {
    this(
        name,
        repositoryId,
        baseUrl,
        repoFilePath,
        gpgKeyUrl,
        enabled,
        gpgCheck,
        true,
        Optional.empty());
  }

  public ZypperRepositorySourceSetup(
      ModuleName name,
      String repositoryId,
      URI baseUrl,
      Path repoFilePath,
      Optional<URI> gpgKeyUrl,
      boolean enabled,
      boolean gpgCheck,
      Optional<Sha256Digest> artifactSha256) {
    this(
        name,
        repositoryId,
        baseUrl,
        repoFilePath,
        gpgKeyUrl,
        enabled,
        gpgCheck,
        true,
        artifactSha256);
  }

  private static void requireArtifactTrust(
      Optional<URI> url, boolean enabled, boolean gpgCheck, Optional<Sha256Digest> checksum) {
    if (url.isPresent() != checksum.isPresent()) {
      throw new IllegalArgumentException(
          "Zypper signing-key URL and SHA-256 checksum must be configured together");
    }
    if (gpgCheck && url.isEmpty()) {
      throw new IllegalArgumentException("Zypper gpgCheck requires a signing-key URL");
    }
    if (enabled && !gpgCheck) {
      throw new IllegalArgumentException("Enabled Zypper repositories must enforce gpgCheck");
    }
  }

  @Override
  public PackageManagerKind packageManager() {
    return PackageManagerKind.ZYPPER;
  }
}
