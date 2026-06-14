package dev.sysboot.core;

import java.util.Objects;

public record ModuleName(String value) {

  public ModuleName {
    Objects.requireNonNull(value, "Module name must not be null");
    value = value.strip();
    if (value.isBlank()) {
      throw new IllegalArgumentException("Module name must not be blank");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
