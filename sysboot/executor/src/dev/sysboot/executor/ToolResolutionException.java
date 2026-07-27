package dev.sysboot.executor;

/** Raised when an external tool cannot be located, downloaded, or verified. */
public final class ToolResolutionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ToolResolutionException(String message) {
    super(message);
  }

  public ToolResolutionException(String message, Throwable cause) {
    super(message, cause);
  }
}
