package dev.sysboot.core;

import java.util.Objects;
import java.util.Optional;

public record ManualModule(ModuleName name, String message, Optional<String> probeCommand)
    implements BootstrapModule {

  public ManualModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(message);
    Objects.requireNonNull(probeCommand);
    if (message.isBlank()) {
      throw new IllegalArgumentException("manual message must not be blank");
    }
  }
}
