package dev.sysboot.core;

import java.net.URI;
import java.util.Objects;

public record BinaryUrl(URI value) {

  public BinaryUrl {
    Objects.requireNonNull(value, "URL must not be null");
    if (!"https".equalsIgnoreCase(value.getScheme())) {
      throw new IllegalArgumentException(
          "Binary download URL must use https scheme, got: " + PublicUrl.from(value));
    }
    if (value.getHost() == null || value.getHost().isBlank()) {
      throw new IllegalArgumentException(
          "Binary download URL must include a host: " + PublicUrl.from(value));
    }
    if (value.getUserInfo() != null) {
      throw new IllegalArgumentException(
          "Binary download URL must not include user-info: " + PublicUrl.from(value));
    }
  }

  @Override
  public String toString() {
    return value.toString();
  }

  public String stateSource() {
    return PublicUrl.from(value);
  }
}
