package dev.sysboot.core;

import java.util.Objects;

public record SkippedPlanEntry(String name, String kind, String reason) {

  public SkippedPlanEntry {
    Objects.requireNonNull(name);
    Objects.requireNonNull(kind);
    Objects.requireNonNull(reason);
    if (name.isBlank()) {
      throw new IllegalArgumentException("Skipped plan entry name must not be blank");
    }
    if (kind.isBlank()) {
      throw new IllegalArgumentException("Skipped plan entry kind must not be blank");
    }
    if (reason.isBlank()) {
      throw new IllegalArgumentException("Skipped plan entry reason must not be blank");
    }
  }
}
