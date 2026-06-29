package dev.sysboot.core;

import java.util.List;
import java.util.Objects;

public record FlatpakModule(ModuleName name, String remote, List<String> appIds, boolean continueOnError)
    implements BootstrapModule {

  public FlatpakModule(ModuleName name, String remote, List<String> appIds) {
    this(name, remote, appIds, false);
  }

  public FlatpakModule {
    Objects.requireNonNull(name, "Module name must not be null");
    Objects.requireNonNull(remote, "Remote must not be null");
    Objects.requireNonNull(appIds, "App ID list must not be null");
    if (remote.isBlank()) {
      remote = "flathub";
    }
    appIds = List.copyOf(appIds);
    if (appIds.isEmpty()) {
      throw new IllegalArgumentException(
          "Flatpak module '" + name.value() + "' must declare at least one app ID");
    }
  }
}
