package dev.sysboot.core;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record AssertModule(
    ModuleName name, String command, String message, String shell, Optional<Path> workingDir)
    implements BootstrapModule {

  public AssertModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(command);
    Objects.requireNonNull(message);
    Objects.requireNonNull(shell);
    Objects.requireNonNull(workingDir);
    if (command.isBlank()) {
      throw new IllegalArgumentException("assert command must not be blank");
    }
    if (message.isBlank()) {
      throw new IllegalArgumentException("assert message must not be blank");
    }
    if (shell.isBlank()) {
      throw new IllegalArgumentException("assert shell must not be blank");
    }
  }
}
