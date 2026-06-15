package dev.sysboot.core;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record PhaseStateEntry(
    String phaseName,
    PhaseStatus status,
    Instant completedAt,
    Optional<String> fingerprint,
    Optional<String> reason) {

  public PhaseStateEntry {
    Objects.requireNonNull(phaseName);
    Objects.requireNonNull(status);
    Objects.requireNonNull(completedAt);
    fingerprint = fingerprint == null ? Optional.empty() : fingerprint;
    reason = reason == null ? Optional.empty() : reason;
  }

  public PhaseStateEntry(String phaseName, PhaseStatus status, Instant completedAt) {
    this(phaseName, status, completedAt, Optional.empty(), Optional.empty());
  }

  public PhaseStateEntry(
      String phaseName, PhaseStatus status, Instant completedAt, Optional<String> fingerprint) {
    this(phaseName, status, completedAt, fingerprint, Optional.empty());
  }
}
