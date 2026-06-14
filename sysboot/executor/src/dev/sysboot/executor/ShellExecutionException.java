package dev.sysboot.executor;

public final class ShellExecutionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ShellExecutionException(String message, Throwable cause) {
    super(message, cause);
  }

  public ShellExecutionException(String message) {
    super(message);
  }
}
