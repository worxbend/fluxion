package dev.sysboot.core;

import java.util.Optional;

public final class ExecutionPausedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String entryName;
  private final String nextPlanEntry;
  private final int exitCode;

  public ExecutionPausedException(
      String entryName, String message, Optional<String> nextPlanEntry, int exitCode) {
    super(message);
    this.entryName = entryName;
    this.nextPlanEntry = nextPlanEntry == null ? null : nextPlanEntry.orElse(null);
    this.exitCode = exitCode;
  }

  public String entryName() {
    return entryName;
  }

  public Optional<String> nextPlanEntry() {
    return Optional.ofNullable(nextPlanEntry);
  }

  public int exitCode() {
    return exitCode;
  }
}
