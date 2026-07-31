package dev.sysboot.core;

import java.util.List;
import java.util.Objects;

/**
 * Packages installed by an ecosystem tool rather than the system package manager.
 *
 * <p>{@code cargo-binstall}, {@code snap}, {@code pipx}, {@code uv tool}, {@code npm -g} and {@code
 * go install} differ only in their argv and their probe, so they share one kind with a backend
 * rather than six near-identical ones. That keeps failure isolation, version pinning, and probing
 * written once.
 */
public record ToolPackagesModule(
    ModuleName name,
    ToolPackageBackend backend,
    List<ToolPackage> packages,
    boolean continueOnError)
    implements BootstrapModule {

  public ToolPackagesModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(backend);
    packages = List.copyOf(Objects.requireNonNull(packages));
    if (packages.isEmpty()) {
      throw new IllegalArgumentException(backend.id() + " requires at least one package");
    }
    if (packages.stream().map(ToolPackage::name).distinct().count() != packages.size()) {
      throw new IllegalArgumentException(backend.id() + " must not repeat a package name");
    }
    packages.forEach(toolPackage -> ToolPackageIdentifierPolicy.requireSafe(backend, toolPackage));
  }

  /** One package, optionally pinned. */
  public record ToolPackage(String name, java.util.Optional<String> version) {

    public ToolPackage {
      Objects.requireNonNull(name);
      Objects.requireNonNull(version);
      if (name.isBlank()) {
        throw new IllegalArgumentException("package name must not be blank");
      }
      if (name.startsWith("-") || name.chars().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException(
            "package name must not be an option or contain controls");
      }
      if (name.matches(".*[\\s$;|&`><].*")) {
        throw new IllegalArgumentException(
            "package name must not contain shell metacharacters: " + name);
      }
      version.ifPresent(
          value -> {
            if (value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
              throw new IllegalArgumentException(
                  "package version must not be blank or contain controls");
            }
          });
    }

    public ToolPackage(String name) {
      this(name, java.util.Optional.empty());
    }
  }
}
