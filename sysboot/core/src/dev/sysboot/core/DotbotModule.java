package dev.sysboot.core;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record DotbotModule(
    ModuleName name,
    Path config,
    String installerVersion,
    String dotbotBinary,
    Optional<String> probeCommand)
    implements BootstrapModule {

  public DotbotModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(config);
    Objects.requireNonNull(installerVersion);
    Objects.requireNonNull(dotbotBinary);
    Objects.requireNonNull(probeCommand);
    if (installerVersion.isBlank()) {
      throw new IllegalArgumentException("installerVersion must not be blank");
    }
    if (dotbotBinary.isBlank()) {
      throw new IllegalArgumentException("dotbotBinary must not be blank");
    }
  }
}
