package dev.sysboot.core;

import java.util.Objects;

public record Checksum(String algorithm, String value) {

  public Checksum {
    Objects.requireNonNull(algorithm, "Checksum algorithm must not be null");
    Objects.requireNonNull(value, "Checksum value must not be null");
    algorithm = algorithm.strip().toUpperCase();
    value = value.strip().toLowerCase();
    if (algorithm.isBlank()) {
      throw new IllegalArgumentException("Checksum algorithm must not be blank");
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException("Checksum value must not be blank");
    }
  }
}
