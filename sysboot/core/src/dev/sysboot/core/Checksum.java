package dev.sysboot.core;

import java.util.Locale;
import java.util.Objects;

public record Checksum(String algorithm, String value) {

  public Checksum {
    Objects.requireNonNull(algorithm, "Checksum algorithm must not be null");
    Objects.requireNonNull(value, "Checksum value must not be null");
    algorithm = normalizeAlgorithm(algorithm);
    value = value.strip().toLowerCase(Locale.ROOT);
    if (algorithm.isBlank()) {
      throw new IllegalArgumentException("Checksum algorithm must not be blank");
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException("Checksum value must not be blank");
    }
  }

  public boolean usesSha256() {
    return "SHA-256".equals(algorithm);
  }

  public boolean hasValidSha256Value() {
    return usesSha256() && value.matches("[0-9a-f]{64}");
  }

  private static String normalizeAlgorithm(String algorithm) {
    String normalized = algorithm.strip().toUpperCase(Locale.ROOT);
    return "SHA256".equals(normalized.replace("-", "")) ? "SHA-256" : normalized;
  }
}
