package dev.sysboot.core;

import java.net.URI;
import java.util.Objects;

public record FlatpakRemoteModule(ModuleName name, String remote, URI url, boolean system)
    implements BootstrapModule {

  public FlatpakRemoteModule {
    Objects.requireNonNull(name, "Module name must not be null");
    Objects.requireNonNull(remote, "Remote must not be null");
    Objects.requireNonNull(url, "Remote URL must not be null");
    if (remote.isBlank()) {
      throw new IllegalArgumentException("Flatpak remote name must not be blank");
    }
  }
}
