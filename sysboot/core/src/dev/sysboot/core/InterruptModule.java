package dev.sysboot.core;

import java.util.List;
import java.util.Objects;

public record InterruptModule(
    ModuleName name,
    String message,
    List<String> instructions,
    InterruptResumeMode resumeFrom,
    int exitCode)
    implements BootstrapModule {

  public InterruptModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(message);
    Objects.requireNonNull(instructions);
    Objects.requireNonNull(resumeFrom);
    if (message.isBlank()) {
      throw new IllegalArgumentException("Interrupt message must not be blank");
    }
    if (exitCode < 0 || exitCode > 255) {
      throw new IllegalArgumentException("Interrupt exit code must be between 0 and 255");
    }
    instructions = List.copyOf(instructions);
  }
}
