package dev.sysboot.core;

import java.net.URI;
import java.util.Objects;

public record BinaryUrl(URI value) {

  public BinaryUrl {
    Objects.requireNonNull(value, "URL must not be null");
    if (!"https".equalsIgnoreCase(value.getScheme())) {
      throw new IllegalArgumentException(
          "Binary download URL must use https scheme, got: " + value);
    }
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
