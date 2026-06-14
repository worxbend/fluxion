package dev.sysboot.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ToolchainModule(
    ModuleName name,
    ToolchainKind kind,
    String installScript,
    List<String> installArgs,
    Optional<String> postInstallEnvSource,
    Optional<String> probeCommand,
    boolean continueOnError)
    implements BootstrapModule {

  public ToolchainModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(kind);
    Objects.requireNonNull(installScript);
    Objects.requireNonNull(installArgs);
    Objects.requireNonNull(postInstallEnvSource);
    Objects.requireNonNull(probeCommand);
    installArgs = List.copyOf(installArgs);
    if (installScript.isBlank()) {
      throw new IllegalArgumentException("installScript must not be blank");
    }
  }
}
