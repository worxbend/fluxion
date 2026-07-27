package dev.sysboot.core;

import java.util.Objects;

public record ShellEnvironmentVariable(String name, String value, boolean sensitive) {

  public ShellEnvironmentVariable {
    Objects.requireNonNull(name);
    Objects.requireNonNull(value);
    if (name.isBlank()) {
      throw new IllegalArgumentException("environment variable name must not be blank");
    }
  }
}
