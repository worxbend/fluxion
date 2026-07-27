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
    boolean autoRefresh)
    implements BootstrapModule {

  public ZypperRepositoryModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(repositoryId);
    Objects.requireNonNull(baseUrl);
    Objects.requireNonNull(repoFilePath);
    gpgKeyUrl = gpgKeyUrl == null ? Optional.empty() : gpgKeyUrl;
    if (repositoryId.isBlank()) {
      throw new IllegalArgumentException("Zypper repository id must not be blank");
    }
  }

  /** Reuses the existing installer rather than restating how to write a .repo file. */
  public ZypperRepositorySourceSetup asSourceSetup() {
    return new ZypperRepositorySourceSetup(
        name, repositoryId, baseUrl, repoFilePath, gpgKeyUrl, enabled, gpgCheck);
  }
}
