package dev.sysboot.core;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record PlanEntryStateEntry(
    String entryName, PlanEntryStatus status, Instant updatedAt, Optional<String> message) {

  public PlanEntryStateEntry {
    Objects.requireNonNull(entryName);
    Objects.requireNonNull(status);
    Objects.requireNonNull(updatedAt);
    message = message == null ? Optional.empty() : message;
    if (entryName.isBlank()) {
      throw new IllegalArgumentException("Plan entry name must not be blank");
    }
  }
}
