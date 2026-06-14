package dev.sysboot.core;

import java.util.Objects;
import java.util.regex.Pattern;

public record PackageName(String value) {

  private static final Pattern UNSAFE_CHARS = Pattern.compile("[ $;|&`><\"'\\\\]");

  public PackageName {
    Objects.requireNonNull(value, "Package name must not be null");
    value = value.strip();
    if (value.isBlank()) {
      throw new IllegalArgumentException("Package name must not be blank");
    }
    if (UNSAFE_CHARS.matcher(value).find()) {
      throw new IllegalArgumentException("Package name contains unsafe shell characters: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
