package dev.sysboot.executor;

public final class StateReadException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public StateReadException(String message, Throwable cause) {
    super(message, cause);
  }
}
