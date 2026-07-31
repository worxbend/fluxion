package dev.sysboot.core;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ToolchainModule(
    ModuleName name,
    ToolchainKind kind,
    String installScript,
    Sha256Digest installScriptSha256,
    List<String> installArgs,
    Optional<String> postInstallEnvSource,
    Optional<String> probeCommand,
    boolean continueOnError)
    implements BootstrapModule {

  public ToolchainModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(kind);
    Objects.requireNonNull(installScript);
    Objects.requireNonNull(installScriptSha256);
    Objects.requireNonNull(installArgs);
    Objects.requireNonNull(postInstallEnvSource);
    Objects.requireNonNull(probeCommand);
    installArgs = List.copyOf(installArgs);
    if (installScript.isBlank()) {
      throw new IllegalArgumentException("installScript must not be blank");
    }
    validateInstallScript(URI.create(installScript));
  }

  private static void validateInstallScript(URI uri) {
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null) {
      throw new IllegalArgumentException("installScript must be HTTPS without user-info");
    }
  }
}
