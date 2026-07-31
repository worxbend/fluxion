package dev.sysboot.core;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * A zypper repository, declared as a step.
 *
 * <p>apt, dnf and pacman each had a repository <em>step kind</em>, but zypper repositories could
 * only be declared under {@code spec.sources}. That asymmetry mattered: openSUSE is a first-class
 * target, and adding the VS Code repository — which is what a real openSUSE bootstrap does — could
 * not be expressed as an ordinary step alongside the packages that need it.
 *
 * <p>The command logic is not duplicated. {@link #asSourceSetup()} converts to the existing source
 * setup so {@code ZypperRepositoryInstaller} drives both paths.
 */
public record ZypperRepositoryModule(
    ModuleName name,
    String repositoryId,
    URI baseUrl,
    Path repoFilePath,
    Optional<URI> gpgKeyUrl,
    boolean enabled,
    boolean gpgCheck,
    boolean autoRefresh,
    Optional<Sha256Digest> artifactSha256)
    implements BootstrapModule {

  public ZypperRepositoryModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(repositoryId);
    Objects.requireNonNull(baseUrl);
    Objects.requireNonNull(repoFilePath);
    gpgKeyUrl = gpgKeyUrl == null ? Optional.empty() : gpgKeyUrl;
    artifactSha256 = artifactSha256 == null ? Optional.empty() : artifactSha256;
    repoFilePath = RepositoryDestinationPolicy.requireZypperRepository(repoFilePath);
    RepositoryIdentifierPolicy.requireSafe(repositoryId, "Zypper repository id");
    SourceUrlPolicy.requireHttps(baseUrl, "Zypper repository base URL");
    gpgKeyUrl.ifPresent(url -> SourceUrlPolicy.requireHttps(url, "Zypper signing-key URL"));
    requireArtifactTrust(gpgKeyUrl, enabled, gpgCheck, artifactSha256);
  }

  public ZypperRepositoryModule(
      ModuleName name,
      String repositoryId,
      URI baseUrl,
      Path repoFilePath,
      Optional<URI> gpgKeyUrl,
      boolean enabled,
      boolean gpgCheck,
      boolean autoRefresh) {
    this(
        name,
        repositoryId,
        baseUrl,
        repoFilePath,
        gpgKeyUrl,
        enabled,
        gpgCheck,
        autoRefresh,
        Optional.empty());
  }

  /** Reuses the existing installer rather than restating how to write a .repo file. */
  public ZypperRepositorySourceSetup asSourceSetup() {
    return new ZypperRepositorySourceSetup(
        name,
        repositoryId,
        baseUrl,
        repoFilePath,
        gpgKeyUrl,
        enabled,
        gpgCheck,
        autoRefresh,
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
}
