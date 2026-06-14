package dev.sysboot.core;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record OhMyZshModule(ModuleName name, Path installDir, Optional<String> probeCommand)
    implements BootstrapModule {

  public OhMyZshModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(installDir);
    Objects.requireNonNull(probeCommand);
  }
}
