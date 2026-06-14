package dev.sysboot.core;

import java.util.Objects;

public record PhaseName(String value) {

  public PhaseName {
    Objects.requireNonNull(value);
    if (value.isBlank()) {
      throw new IllegalArgumentException("Phase name must not be blank");
    }
  }
}
