package dev.sysboot.core;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record Sha256Digest(String value) {

  private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]{64}");

  public Sha256Digest {
    Objects.requireNonNull(value, "SHA-256 digest must not be null");
    value = value.strip().toLowerCase(Locale.ROOT);
    if (!HEX.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "SHA-256 digest must contain exactly 64 hexadecimal characters");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
