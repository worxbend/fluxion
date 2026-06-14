package dev.sysboot.core;

import java.time.Instant;
import java.util.Objects;

public record PhaseStateEntry(String phaseName, PhaseStatus status, Instant completedAt) {

  public PhaseStateEntry {
    Objects.requireNonNull(phaseName);
    Objects.requireNonNull(status);
    Objects.requireNonNull(completedAt);
  }
}
