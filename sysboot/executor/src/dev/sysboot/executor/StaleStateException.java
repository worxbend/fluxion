package dev.sysboot.executor;

/** Raised when a saved state file belongs to a different manifest execution plan. */
public final class StaleStateException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public StaleStateException(String message) {
    super(message);
  }
}
