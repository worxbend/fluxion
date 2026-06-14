package dev.sysboot.core;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record DefaultShellModule(ModuleName name, Path shellPath, Optional<String> probeCommand)
    implements BootstrapModule {

  public DefaultShellModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(shellPath);
    Objects.requireNonNull(probeCommand);
  }
}
