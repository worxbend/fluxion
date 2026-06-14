package dev.sysboot.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ShellCommandModule(
    ModuleName name,
    List<String> commands,
    String shell,
    Optional<Path> workingDir,
    boolean continueOnError,
    Optional<String> probeCommand)
    implements BootstrapModule {

  public ShellCommandModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(commands);
    Objects.requireNonNull(shell);
    Objects.requireNonNull(workingDir);
    Objects.requireNonNull(probeCommand);
    commands = List.copyOf(commands);
    if (commands.isEmpty()) {
      throw new IllegalArgumentException("commands must not be empty");
    }
    if (shell.isBlank()) {
      throw new IllegalArgumentException("shell must not be blank");
    }
  }
}
