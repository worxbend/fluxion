package dev.sysboot.core;

import java.net.URI;
import java.util.Objects;

public record FlatpakRemoteSourceSetup(ModuleName name, String remote, URI url, boolean system)
    implements SourceSetup {

  public FlatpakRemoteSourceSetup {
    Objects.requireNonNull(name, "Source name must not be null");
    Objects.requireNonNull(remote, "Flatpak remote name must not be null");
    Objects.requireNonNull(url, "Flatpak remote URL must not be null");
    if (remote.isBlank()) {
      throw new IllegalArgumentException("Flatpak remote name must not be blank");
    }
  }

  @Override
  public PackageManagerKind packageManager() {
    return PackageManagerKind.FLATPAK;
  }
}
