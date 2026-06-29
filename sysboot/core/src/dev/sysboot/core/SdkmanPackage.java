package dev.sysboot.core;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public record SdkmanPackage(String candidate, Optional<String> version) {

  private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9._+-]+");

  public SdkmanPackage(String candidate, Optional<String> version) {
    this.candidate = validate("SDKMAN candidate", candidate);
    this.version = normalizeVersion(version);
  }

  public SdkmanPackage(String candidate) {
    this(candidate, Optional.empty());
  }

  public String itemKey() {
    return version.map(value -> candidate + "@" + value).orElse(candidate);
  }

  private static Optional<String> normalizeVersion(Optional<String> version) {
    return Objects.requireNonNull(version, "SDKMAN version must not be null")
        .map(value -> validate("SDKMAN version", value));
  }

  private static String validate(String label, String value) {
    Objects.requireNonNull(value, label + " must not be null");
    String normalized = value.strip();
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    if (!SAFE_VALUE.matcher(normalized).matches()) {
      throw new IllegalArgumentException(label + " contains unsafe shell characters: " + value);
    }
    return normalized;
  }
}
