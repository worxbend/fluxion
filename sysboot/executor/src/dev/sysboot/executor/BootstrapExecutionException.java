package dev.sysboot.executor;

/** A live bootstrap completed with one or more hard execution failures. */
public final class BootstrapExecutionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public BootstrapExecutionException(String message) {
    super(message);
  }
}
