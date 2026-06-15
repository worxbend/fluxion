package dev.sysboot.core;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record PhaseStateEntry(
    String phaseName, PhaseStatus status, Instant completedAt, Optional<String> fingerprint) {

  public PhaseStateEntry {
    Objects.requireNonNull(phaseName);
    Objects.requireNonNull(status);
    Objects.requireNonNull(completedAt);
    fingerprint = fingerprint == null ? Optional.empty() : fingerprint;
  }

  public PhaseStateEntry(String phaseName, PhaseStatus status, Instant completedAt) {
    this(phaseName, status, completedAt, Optional.empty());
  }
}
