package dev.sysboot.core;

import java.util.Objects;
import java.util.Optional;

public record NerdFontModule(
    ModuleName name,
    String installerVersion,
    String nerdfontBinary,
    NerdFontConfig config,
    Optional<String> probeCommand)
    implements BootstrapModule {

  public NerdFontModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(installerVersion);
    Objects.requireNonNull(nerdfontBinary);
    Objects.requireNonNull(config);
    Objects.requireNonNull(probeCommand);
    if (installerVersion.isBlank()) {
      throw new IllegalArgumentException("installerVersion must not be blank");
    }
    if (nerdfontBinary.isBlank()) {
      throw new IllegalArgumentException("nerdfontBinary must not be blank");
    }
  }
}
