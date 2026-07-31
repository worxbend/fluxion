package dev.sysboot.executor;

/** Cooperative execution cancellation reached a safe item boundary. */
public final class ExecutionCancelledException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ExecutionCancelledException() {
    super("Bootstrap cancelled at a safe execution boundary");
  }

  public ExecutionCancelledException(Throwable cause) {
    super("Bootstrap cancelled at a safe execution boundary", cause);
  }
}
