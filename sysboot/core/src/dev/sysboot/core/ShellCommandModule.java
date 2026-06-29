package dev.sysboot.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ShellCommandModule(
    ModuleName name,
    List<ShellCommandItem> items,
    String shell,
    Optional<Path> workingDir,
    boolean continueOnError,
    Optional<String> probeCommand)
    implements BootstrapModule {

  public ShellCommandModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(items);
    Objects.requireNonNull(shell);
    Objects.requireNonNull(workingDir);
    Objects.requireNonNull(probeCommand);
    items = List.copyOf(items);
    if (items.isEmpty()) {
      throw new IllegalArgumentException("commands must not be empty");
    }
    if (shell.isBlank()) {
      throw new IllegalArgumentException("shell must not be blank");
    }
  }

  public List<String> commands() {
    return items.stream()
        .map(item -> item.shellCommand().orElseGet(() -> String.join(" ", item.argv().orElseThrow())))
        .toList();
  }
}
