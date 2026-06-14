package dev.sysboot.executor;

public final class StateWriteException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public StateWriteException(String message, Throwable cause) {
    super(message, cause);
  }
}
