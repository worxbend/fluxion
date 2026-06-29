package dev.sysboot.core;

import java.util.Objects;
import java.util.Optional;

public record HostFacts(
    String osFamily,
    Optional<String> distribution,
    Optional<String> version,
    Optional<String> codename,
    String architecture) {

  public HostFacts {
    osFamily = requiredValue(osFamily, "OS family");
    distribution = optionalValue(distribution, "Distribution");
    version = optionalValue(version, "Version");
    codename = optionalValue(codename, "Codename");
    architecture = requiredValue(architecture, "Architecture");
  }

  private static String requiredValue(String value, String label) {
    Objects.requireNonNull(value, label + " must not be null");
    String stripped = value.strip();
    if (stripped.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return stripped;
  }

  private static Optional<String> optionalValue(Optional<String> value, String label) {
    Objects.requireNonNull(value, label + " must not be null");
    return value.map(String::strip).filter(stripped -> !stripped.isBlank());
  }
}
