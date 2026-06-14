package dev.sysboot.core;

import java.util.Objects;

public record ProfileName(String value) {

  public ProfileName {
    Objects.requireNonNull(value, "Profile name must not be null");
    value = value.strip();
    if (value.isBlank()) {
      throw new IllegalArgumentException("Profile name must not be blank");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
